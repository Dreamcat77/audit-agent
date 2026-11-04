package com.fuquan.audit.integration.http;

import com.fuquan.audit.common.privacy.ClientReviewIdentity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Decorates only AI JSON transport; OCR multipart must remain streaming. */
@Component
public class ClientIdentityHttpBoundary implements BeanPostProcessor {
    private final ObjectProvider<ClientReviewIdentity> identity;
    public ClientIdentityHttpBoundary(ObjectProvider<ClientReviewIdentity> identity){this.identity=identity;}
    @Override public Object postProcessAfterInitialization(Object bean,String name){
        if(!name.equals("aiRestClient") || !(bean instanceof RestClient client)) return bean;
        return client.mutate().requestInterceptor((request,body,execution)->{
            request.getHeaders().set(ClientReviewIdentity.HEADER,identity.getObject().internalHeader());
            return execution.execute(request,body);
        }).build();
    }
}
