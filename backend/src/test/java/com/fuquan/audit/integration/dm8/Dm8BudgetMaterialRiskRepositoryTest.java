package com.fuquan.audit.integration.dm8;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.budget.application.BudgetMaterialRisk;
import java.sql.SQLException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.dao.DuplicateKeyException;
class Dm8BudgetMaterialRiskRepositoryTest {
    @Test void onlyDmUniqueViolationCanBecomeAnIdempotentReplay(){
        for(int code:new int[]{-6602,-6601,-2106}){
            var jdbc=mock(JdbcTemplate.class);var failure=new UncategorizedSQLException("synthetic","INSERT",new SQLException("synthetic","23000",code));
            doThrow(failure).when(jdbc).update(anyString(),any(Object[].class),any(int[].class));
            var repo=new Dm8BudgetMaterialRiskRepository(jdbc,new ObjectMapper());
            var result=new BudgetMaterialRisk.Result(UUID.randomUUID(),"synthetic",java.time.Instant.now().toString(),List.of(),List.of());
            if(code==-6602)assertThatThrownBy(()->repo.insert(result)).isInstanceOf(DuplicateKeyException.class);
            else assertThatThrownBy(()->repo.insert(result)).isSameAs(failure);
        }
    }
}
