package org.demo.shopapp.web;

import org.demo.shopapp.order.OrderCartCleanupService;
import org.demo.shopapp.order.OrderRepository;
import org.demo.shopapp.order.OrderStateService;
import org.demo.shopapp.payment.BankAuthorizationClient;
import org.demo.shopapp.payment.BankAuthorizationRedeemRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/payment")
public class ShopPaymentCallbackController {

    private final OrderStateService orderStateService;
    private final BankAuthorizationClient bankAuthorizationClient;
    private final OrderCartCleanupService cartCleanupService;

    public ShopPaymentCallbackController(
            OrderStateService orderStateService,
            BankAuthorizationClient bankAuthorizationClient,
            OrderCartCleanupService cartCleanupService
    ) {
        this.orderStateService = orderStateService;
        this.bankAuthorizationClient = bankAuthorizationClient;
        this.cartCleanupService = cartCleanupService;
    }

    @GetMapping("/callback")
    String callback(
            @RequestParam String authorizationId,
            @RequestParam String state,
            @RequestParam String status,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String reason
    ) {
        var order = orderStateService.findByAuthorizationId(authorizationId);
        if (!state.equals(order.paymentState())) {
            return redirect(order.orderNumber(), "failed");
        }
        if (order.status().name().equals("PAID")) {
            cleanupCart(order);
            return redirect(order.orderNumber(), "paid");
        }

        if ("approved".equals(status) && code != null && !code.isBlank()) {
            try {
                var bankResult = bankAuthorizationClient.redeem(
                        authorizationId,
                        new BankAuthorizationRedeemRequest(code, state)
                );
                if (!order.orderNumber().equals(bankResult.orderId())
                        || order.total().compareTo(bankResult.amount()) != 0
                        || !order.currency().equals(bankResult.currency())
                        || !"COMPLETED".equals(bankResult.status())
                        || bankResult.customerAccountNumber() == null
                        || !bankResult.customerAccountNumber().matches("\\d{26}")) {
                    throw new IllegalStateException("Bank response does not match the order.");
                }
                var paid = orderStateService.markPaid(
                        order.id(), order.paymentIdempotencyKey(), bankResult.referenceNumber(),
                        bankResult.customerAccountNumber()
                );
                cleanupCart(paid);
                return redirect(paid.orderNumber(), "paid");
            } catch (RuntimeException exception) {
                var failed = orderStateService.markFailed(order.id(), "Bank payment confirmation failed.");
                return redirect(failed.orderNumber(), "failed");
            }
        }

        var failed = orderStateService.markFailed(order.id(), switch (reason == null ? "" : reason) {
            case "insufficient_funds" -> "Brak środków na rachunku.";
            default -> switch (status) {
            case "cancelled" -> "Payment was cancelled by the customer.";
            case "expired" -> "Payment authorization expired.";
            default -> "Payment was not completed by the bank.";
            };
        });
        return redirect(failed.orderNumber(), "failed");
    }

    private void cleanupCart(OrderRepository.OrderRow order) {
        try {
            cartCleanupService.cleanupPaidOrder(order);
        } catch (RuntimeException ignored) {
            // Payment is already confirmed; cart cleanup can be retried on the next callback.
        }
    }

    private static String redirect(String orderNumber, String payment) {
        return "redirect:/novatech_shop.html?payment=" + payment + "&order=" + orderNumber;
    }
}
