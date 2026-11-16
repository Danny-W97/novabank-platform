package org.demo.bankdemo.web;

import org.demo.bankdemo.payment.PaymentException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PaymentApiAvailabilityTest {
    @RestController static class FailingApi {
        @GetMapping("/api/probe/lock") String lock() { throw new CannotAcquireLockException("private SQL detail"); }
        @GetMapping("/api/probe/commit") String commit() { throw new TransactionSystemException("private database detail"); }
        @GetMapping("/api/probe/decline") String decline() { throw new PaymentException("Business refusal"); }
        @GetMapping("/api/probe/jpa") String jpa() {throw new org.springframework.orm.jpa.JpaSystemException(new IllegalStateException("private detail"));}
        @GetMapping("/api/probe/offline") String offline() {throw new org.springframework.dao.DataAccessResourceFailureException("private detail");}
    }

    @Test void lockAndUncertainCommitReturnSafeRetryWithoutInventingDecline() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new FailingApi()).setControllerAdvice(new PaymentApiExceptionHandler()).build();
        for (var path : new String[]{"lock", "commit", "jpa", "offline"}) mvc.perform(get("/api/probe/" + path))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("PAYMENT_OUTCOME_UNCONFIRMED"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
        mvc.perform(get("/api/probe/decline")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PAYMENT_REJECTED"));
    }
}
