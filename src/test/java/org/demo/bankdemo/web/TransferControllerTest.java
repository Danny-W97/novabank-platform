package org.demo.bankdemo.web;

import jakarta.servlet.http.HttpSession;
import org.demo.bankdemo.audit.OperationAuditService;
import org.demo.bankdemo.dashboard.DashboardService;
import org.demo.bankdemo.dashboard.DashboardView;
import org.demo.bankdemo.profile.TransactionPinService;
import org.demo.bankdemo.recipient.RecipientService;
import org.demo.bankdemo.transfer.TransferCommand;
import org.demo.bankdemo.transfer.TransferDraftStore;
import org.demo.bankdemo.transfer.TransferException;
import org.demo.bankdemo.transfer.TransferForm;
import org.demo.bankdemo.transfer.TransferPreview;
import org.demo.bankdemo.transfer.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferControllerTest {

    private static final String EMAIL = "anna.nowak@example.pl";

    @Mock
    private DashboardService dashboardService;

    @Mock
    private TransferService transferService;

    @Mock
    private TransferDraftStore draftStore;

    @Mock
    private RecipientService recipientService;

    @Mock
    private TransactionPinService transactionPinService;

    @Mock
    private OperationAuditService auditService;

    @Mock
    private org.demo.bankdemo.transfer.TransferConfirmationWorkflow confirmationWorkflow;

    @Mock
    private Authentication authentication;

    @Mock
    private HttpSession session;

    private TransferController controller;

    @BeforeEach
    void setUp() {
        controller = new TransferController(
                dashboardService,
                transferService,
                draftStore,
                recipientService,
                transactionPinService,
                auditService,
                confirmationWorkflow
        );
    }

    @Test
    void wrongPinReturnsConfirmationPageWithInlineMessage() {
        var command = command();
        var preview = preview();
        when(authentication.getName()).thenReturn(EMAIL);
        when(draftStore.find(session, command.idempotencyKey()))
                .thenReturn(Optional.of(command));
        doThrow(new TransferException("pin", "Podano zły pin transakcyjny"))
                .when(confirmationWorkflow)
                .execute(EMAIL, command, "9999", null, session);
        when(confirmationWorkflow.prepare(EMAIL, command, session)).thenReturn(
                new org.demo.bankdemo.transfer.TransferConfirmationWorkflow.Preparation(false, "", false));
        when(transferService.preview(EMAIL, command)).thenReturn(preview);
        when(transactionPinService.isConfigured(EMAIL)).thenReturn(true);
        when(dashboardService.dashboardFor(EMAIL)).thenReturn(dashboard());
        var model = new ExtendedModelMap();
        var redirectAttributes = new RedirectAttributesModelMap();

        var view = controller.execute(
                authentication,
                command.idempotencyKey(),
                "9999",
                session,
                model,
                redirectAttributes
        );

        assertThat(view).isEqualTo("transfer/confirm");
        assertThat(model.get("pinError"))
                .isEqualTo("Podano zły pin transakcyjny");
        assertThat(model.get("preview")).isEqualTo(preview);
        assertThat(model.get("transferForm")).isInstanceOf(TransferForm.class);
        assertThat(redirectAttributes.getFlashAttributes()).isEmpty();
        verify(transferService, never()).execute(EMAIL, command);
    }

    @Test
    void editRestoresSerializableTransferForm() {
        var command = command();
        when(draftStore.find(session, command.idempotencyKey()))
                .thenReturn(Optional.of(command));
        var redirectAttributes = new RedirectAttributesModelMap();

        var view = controller.edit(
                command.idempotencyKey(),
                session,
                redirectAttributes
        );

        assertThat(view).isEqualTo("redirect:/transfer");
        assertThat(redirectAttributes.getFlashAttributes().get("transferForm"))
                .isInstanceOf(TransferForm.class)
                .isInstanceOf(Serializable.class);
    }

    private static TransferCommand command() {
        return new TransferCommand(
                UUID.randomUUID().toString(),
                "Anna Nowak",
                "44266350967334749815879411",
                new BigDecimal("100.00"),
                "Test przelewu"
        );
    }

    private static TransferPreview preview() {
        return new TransferPreview(
                "Anna Nowak",
                "44 2663 5096 7334 7498 1587 9411",
                "100,00 zł",
                "Test przelewu"
        );
    }

    private static DashboardView dashboard() {
        return new DashboardView(
                "Anna",
                "Anna Nowak",
                "AN",
                EMAIL,
                "Nie podano",
                "NB-000001",
                "61 1090 1014 0000 0712 1981 2874",
                "10 000,00 zł",
                "Aktywne",
                "27 lipca 2026",
                "lipiec",
                "Brak operacji",
                "0,00 zł",
                0,
                "0,00 zł",
                0,
                List.of()
        );
    }
}
