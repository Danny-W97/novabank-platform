package org.demo.bankdemo.web;

import org.demo.bankdemo.dashboard.DashboardService;
import org.demo.bankdemo.dashboard.DashboardView;
import org.demo.bankdemo.dashboard.ExpenseChartService;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.ui.ExtendedModelMap;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FrontendControllerTest {

    @Test
    void aboutReturnsPublicAboutView() {
        var controller = new FrontendController(mock(DashboardService.class), mock(ExpenseChartService.class));

        assertThat(controller.about()).isEqualTo("about");
    }

    @Test
    void dashboardUsesEmailFromAuthentication() {
        var dashboardService = mock(DashboardService.class);
        var authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn("anna.nowak@example.pl");
        when(dashboardService.dashboardFor("anna.nowak@example.pl"))
                .thenReturn(dashboardView());
        var expenseChartService = mock(ExpenseChartService.class);
        when(expenseChartService.expensesFor("anna.nowak@example.pl")).thenReturn(List.of());
        var controller = new FrontendController(dashboardService, expenseChartService);
        var model = new ExtendedModelMap();

        var viewName = controller.dashboard(authentication, model);

        assertThat(viewName).isEqualTo("dashboard/index");
        assertThat(model.get("fullName")).isEqualTo("Anna Nowak");
        assertThat(model.get("accountNumber")).isEqualTo("61 1090 1014 0000 0712 1981 2874");
        verify(dashboardService).dashboardFor("anna.nowak@example.pl");
    }

    private static DashboardView dashboardView() {
        return new DashboardView(
                "Anna",
                "Anna Nowak",
                "AN",
                "anna.nowak@example.pl",
                "Nie podano",
                "NB-000007",
                "61 1090 1014 0000 0712 1981 2874",
                "10 000,00 zł",
                "Aktywne",
                "Sobota, 25 lipca 2026",
                "lipiec",
                "Brak operacji",
                "−0,00 zł",
                0,
                "+10 000,00 zł",
                1,
                List.of()
        );
    }
}
