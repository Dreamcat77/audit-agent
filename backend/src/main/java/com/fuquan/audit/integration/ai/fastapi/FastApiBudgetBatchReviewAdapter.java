package com.fuquan.audit.integration.ai.fastapi;

import com.fuquan.audit.budget.application.ai.BudgetBatchReviewPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component @Profile({"local-dev & local-ai","dev29","customer"})
public class FastApiBudgetBatchReviewAdapter implements BudgetBatchReviewPort {
    private final RestClient client;
    public FastApiBudgetBatchReviewAdapter(@Qualifier("aiRestClient") RestClient client) {
        var factory=new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofSeconds(5));factory.setReadTimeout(java.time.Duration.ofSeconds(185));
        this.client=client.mutate().requestFactory(factory).build();
    }
    public Result analyze(Request request){return client.post().uri("/ai/v1/budgets/review-batch").body(request).retrieve().body(Result.class);}
}
