package com.fuquan.audit.common.material.application.port;

import java.util.List;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.*;

/** Bounded extraction transport. A timeout is an unknown outcome, not permission to replay. */
public interface MaterialUnitAiPort {
    interface Admission extends AutoCloseable { @Override void close(); }
    /** Waiting for a slot happens before a durable external-call claim. */
    default Admission admit(){return ()->{};}
    String policy();
    record Reply(Result result, int callCount) {}
    Reply extractUnit(String kind,String id,String sha,List<Segment> segments,String policy,int callLimit,long timeoutMillis);
}
