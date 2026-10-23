package org.demo.bankdemo.transfer;

import org.demo.bankdemo.audit.OperationAuditService;
import org.demo.bankdemo.profile.TransactionPinService;
import org.demo.bankdemo.profile.TwoFactorService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import java.math.BigDecimal;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransferConfirmationWorkflowTest {
    private final TransferService transfers = mock(TransferService.class);
    private final TransactionPinService pins = mock(TransactionPinService.class);
    private final TwoFactorService twoFactor = mock(TwoFactorService.class);
    private final TransferSecurityService security = mock(TransferSecurityService.class);
    private final TransferTotpChallengeService challenges = mock(TransferTotpChallengeService.class);
    private final OperationAuditService audit = mock(OperationAuditService.class);
    private final TransferConfirmationWorkflow workflow = new TransferConfirmationWorkflow(
            transfers, pins, twoFactor, security, challenges, audit);
    private final TransferCommand command = new TransferCommand("operation", "Recipient",
            "44266350967334749815879411", new BigDecimal("100.00"), "Test");

    @Test void completedRetryReturnsStoredResultWithoutAnotherPinOrTotpConsumption() {
        var result = new TransferResult("REF", "Recipient", "account", "100", "Test", "now");
        when(transfers.findCompleted("owner", command)).thenReturn(Optional.of(result));
        assertThat(workflow.execute("owner", command, null, null, new MockHttpSession()).result()).isSameAs(result);
        verifyNoInteractions(pins, twoFactor, security, challenges, audit);
        verify(transfers, never()).execute(any(), any());
    }

    @Test void mismatchedCompletedCommandNeverFallsThroughToNewPayment() {
        when(transfers.findCompleted("other-owner", command)).thenThrow(new TransferException(null, "Conflict"));
        assertThatThrownBy(() -> workflow.execute("other-owner", command, null, null, new MockHttpSession()))
                .isInstanceOf(TransferException.class);
        verifyNoInteractions(pins, challenges, security);
        verify(transfers, never()).execute(any(), any());
    }

    @Test void unexecutedTransferStillRequiresItsChallengeAndDoesNotExecuteOnReplayCode() {
        var session = new MockHttpSession();
        when(transfers.findCompleted("owner", command)).thenReturn(Optional.empty());
        when(security.evaluate("owner", command, session)).thenReturn(new TransferSecurityService.Requirement(true, "risk"));
        when(twoFactor.isEnabled("owner")).thenReturn(true);
        when(challenges.verify(session, "owner", command, "used"))
                .thenReturn(TransferTotpChallengeService.VerificationResult.INVALID_CODE);
        assertThat(workflow.execute("owner", command, "1234", "used", session).result()).isNull();
        verify(pins).verifyForTransfer("owner", "1234");
        verify(transfers, never()).execute(any(), any());
    }
}
