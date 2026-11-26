"""Request-local cancellation, propagated by AnyIO to its worker thread."""
from contextvars import ContextVar
from threading import Event

CURRENT: ContextVar[Event | None] = ContextVar('ocr_cancel_event', default=None)


class OcrCancelled(Exception):
    pass


def check_cancelled():
    event = CURRENT.get()
    if event is not None and event.is_set():
        raise OcrCancelled('OCR request cancelled')
