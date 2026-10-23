package org.demo.bankdemo.web;

import org.demo.bankdemo.currency.*;
import org.demo.bankdemo.dashboard.DashboardService;
import org.demo.bankdemo.dashboard.DashboardView;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ExchangeControllerTest {
    private static final String EMAIL = "client@example.pl";

    @Test
    void twoTabsKeepTheirServerIssuedQuoteKeysAndNeverSubmitTheOtherQuote() {
        var service = mock(ExchangeService.class);
        var dashboard = mock(DashboardService.class);
        when(dashboard.dashboardFor(EMAIL)).thenReturn(new DashboardView("Test", "Test", "T", EMAIL,
                "", "", "", "0", "ACTIVE", "", "", "", "0", 0L, "0", 0L, List.of()));
        var controller = new ExchangeController(service, dashboard);
        var authentication = new UsernamePasswordAuthenticationToken(EMAIL, "synthetic");
        var a = form("100.00");
        var b = form("200.00");
        var quoteA = quote("100.00", "25.00");
        var quoteB = quote("200.00", "50.00");
        when(service.prepareQuote(eq(EMAIL), any(), any(), any()))
                .thenReturn(new ExchangeService.PreparedQuote("server-A", quoteA),
                        new ExchangeService.PreparedQuote("server-B", quoteB));
        var modelA = new ExtendedModelMap();
        var modelB = new ExtendedModelMap();
        assertThat(controller.quote(authentication, a, modelA)).isEqualTo("exchange/confirm");
        assertThat(controller.quote(authentication, b, modelB)).isEqualTo("exchange/confirm");
        assertThat(a.getIdempotencyKey()).isEqualTo("server-A");
        assertThat(b.getIdempotencyKey()).isEqualTo("server-B");
        assertThat(modelA.get("quote")).isSameAs(quoteA);
        assertThat(modelB.get("quote")).isSameAs(quoteB);
        assertThat(controller.execute(authentication, a, new ExtendedModelMap())).isEqualTo("redirect:/exchange?success");
        assertThat(controller.execute(authentication, b, new ExtendedModelMap())).isEqualTo("redirect:/exchange?success");
        verify(service).executePrepared(EMAIL, "server-A");
        verify(service).executePrepared(EMAIL, "server-B");
    }

    @Test
    void transientLockFailureIs503WithRetryInstructionInsteadOf500() throws Exception {
        var service = mock(ExchangeService.class);
        when(service.executePrepared(EMAIL, "server-key")).thenThrow(new CannotAcquireLockException("busy"));
        var mvc = MockMvcBuilders.standaloneSetup(new ExchangeController(service, mock(DashboardService.class)))
                .setControllerAdvice(new BankOperationExceptionHandler()).build();
        mvc.perform(post("/exchange/execute").param("idempotencyKey", "server-key")
                        .principal(new UsernamePasswordAuthenticationToken(EMAIL, "synthetic")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"));
    }

    private static ExchangeForm form(String amount) {
        var form = new ExchangeForm();
        form.setAmount(amount);
        form.setIdempotencyKey("client-supplied-key-is-not-trusted");
        return form;
    }

    private static ExchangeService.Quote quote(String source, String target) {
        return new ExchangeService.Quote(CurrencyCode.PLN, CurrencyCode.EUR, new BigDecimal(source),
                new BigDecimal(target), new BigDecimal("0.25000000"), Instant.now());
    }
}
