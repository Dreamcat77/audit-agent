"""Bounded FIFO admission shared by the service's model gateway, across routes."""
from collections import deque
from contextlib import contextmanager
from dataclasses import replace
from functools import wraps
import logging
import math
from threading import Condition
import time

from audit_ai.logging import log_event


class AdmissionUnavailable(Exception):
    pass


class ProviderAdmission:
    def __init__(self, capacity, max_waiters=16):
        self.capacity=capacity
        self.max_waiters=max_waiters
        self._condition=Condition()
        self._waiting=deque()
        self._active=0

    @contextmanager
    def slot(self, timeout):
        started=time.monotonic()
        deadline=started+timeout
        ticket=object()
        queued=False
        with self._condition:
            if not math.isfinite(timeout) or timeout<=0 or len(self._waiting)>=self.max_waiters:
                raise AdmissionUnavailable()
            self._waiting.append(ticket)
            try:
                while self._active>=self.capacity or self._waiting[0] is not ticket:
                    queued=True
                    remaining=deadline-time.monotonic()
                    if remaining<=0: raise AdmissionUnavailable()
                    self._condition.wait(remaining)
                if deadline<=time.monotonic(): raise AdmissionUnavailable()
                self._waiting.popleft()
                self._active+=1
            except BaseException:
                if ticket in self._waiting:self._waiting.remove(ticket)
                self._condition.notify_all()
                raise
        try:
            yield time.monotonic()-started if queued else 0.0
        finally:
            # A transport that is still running keeps its slot. We do not spawn
            # detached inference threads or claim cancellation of remote work.
            with self._condition:
                self._active-=1
                self._condition.notify_all()


def provider_admitted(function):
    @wraps(function)
    def wrapped(self, **kwargs):
        from audit_ai.model.gateway import ModelGatewayFailure
        settings=self._settings
        operation=kwargs['operation']
        timeout=settings.model_read_timeout_seconds
        if operation=='CONTRACT_TEXT_ANALYSIS':timeout=settings.contract_model_read_timeout_seconds
        elif operation in {'MATERIAL_FIELD_EXTRACTION','BUDGET_BATCH_REVIEW'}:timeout=settings.material_model_read_timeout_seconds
        elif operation in {'PROCUREMENT_CASE_REVIEW','CONTRACT_CASE_ANALYSIS'}:timeout=settings.case_model_read_timeout_seconds
        if kwargs.get('provider_timeout_seconds') is not None:timeout=min(timeout,kwargs['provider_timeout_seconds'])
        started=time.monotonic()
        try:
            with self._provider_admission.slot(timeout) as waited:
                remaining=timeout-waited
                if remaining<=0:raise AdmissionUnavailable()
                if waited:kwargs['provider_timeout_seconds']=remaining
                log_event(logging.getLogger(__name__),'PROVIDER_ADMISSION',stage='CAPACITY',outcome='ADMITTED',
                    operation=operation,request_id=kwargs['request_id'],queue_wait_ms=round(waited*1000),
                    provider_capacity=self._provider_admission.capacity)
                try:
                    completion=function(self,**kwargs)
                except ModelGatewayFailure as failure:
                    failure.duration_ms+=round(waited*1000)
                    raise
                return replace(completion,duration_ms=completion.duration_ms+round(waited*1000))
        except AdmissionUnavailable:
            duration=round((time.monotonic()-started)*1000)
            log_event(logging.getLogger(__name__),'PROVIDER_ADMISSION',stage='CAPACITY',outcome='NOT_CALLED',
                operation=operation,request_id=kwargs['request_id'],queue_wait_ms=duration,
                provider_capacity=self._provider_admission.capacity)
            raise ModelGatewayFailure(reason='PROVIDER_DISPATCH_NOT_STARTED',retryable=False,duration_ms=duration) from None
    return wrapped
