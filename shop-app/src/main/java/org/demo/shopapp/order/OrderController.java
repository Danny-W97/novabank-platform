package org.demo.shopapp.order;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpSession;
import org.demo.shopapp.auth.ShopUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderPaymentService orderPaymentService;
    private final CardOrderPaymentService cardOrderPaymentService;
    private final ExternalCardPaymentService externalCardPaymentService;
    private final CardCheckoutSessionService cardCheckoutSessionService;
    private final RefundService refundService;
    private final PurchaseCancellationService cancellations;

    public OrderController(
            OrderService orderService,
            OrderPaymentService orderPaymentService,
            RefundService refundService,
            CardOrderPaymentService cardOrderPaymentService,
            ExternalCardPaymentService externalCardPaymentService,
            CardCheckoutSessionService cardCheckoutSessionService,
            PurchaseCancellationService cancellations
    ) {
        this.orderService = orderService;
        this.orderPaymentService = orderPaymentService;
        this.cardOrderPaymentService = cardOrderPaymentService;
        this.externalCardPaymentService = externalCardPaymentService;
        this.cardCheckoutSessionService = cardCheckoutSessionService;
        this.refundService = refundService;
        this.cancellations=cancellations;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    OrderResponse create(
            @AuthenticationPrincipal ShopUserDetails user,
            @Valid @RequestBody CreateOrderRequest request
    ) {
        return orderService.create(
                user.getCustomerId()
        );
    }

    @GetMapping
    List<OrderResponse> list(@AuthenticationPrincipal ShopUserDetails user) {
        return orderService.list(user.getCustomerId());
    }

    @GetMapping("/{orderId}")
    OrderResponse get(
            @AuthenticationPrincipal ShopUserDetails user,
            @PathVariable long orderId
    ) {
        return orderService.get(orderId, user.getCustomerId());
    }

    @PostMapping("/{orderId}/pay")
    OrderResponse pay(
            @AuthenticationPrincipal ShopUserDetails user,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @PathVariable long orderId
    ) {
        return orderPaymentService.pay(
                orderId,
                user.getCustomerId(),
                idempotencyKey
        );
    }

    public record CancelPurchase(java.util.UUID requestId,boolean confirmed){}
    @PostMapping("/{orderId}/cancel-purchase")
    Object cancelPurchase(@AuthenticationPrincipal ShopUserDetails user,@PathVariable long orderId,@RequestBody CancelPurchase request,HttpSession session){
        if(!request.confirmed())throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,"Potwierdź rezygnację z zakupu.");
        var result=cancellations.cancel(orderId,user.getCustomerId(),request.requestId(),"CUSTOMER","Klient potwierdził rezygnację z niedokończonego zakupu.");
        if("CANCELLED".equals(result.status()))cardCheckoutSessionService.clearForOrder(orderId,session);
        return result;
    }
    @PostMapping("/{orderId}/refresh-payment")
    Object refreshPayment(@AuthenticationPrincipal ShopUserDetails user,@PathVariable long orderId){return cancellations.refresh(orderId,user.getCustomerId());}

    @PostMapping("/{orderId}/refund")
    RefundResponse refund(
            @AuthenticationPrincipal ShopUserDetails user,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @PathVariable long orderId,
            @Valid @RequestBody RefundRequest request
    ) {
        return refundService.refund(orderId, user.getCustomerId(), idempotencyKey, request.reason());
    }

    @PostMapping("/{orderId}/card-pay")
    OrderResponse cardPay(
            @AuthenticationPrincipal ShopUserDetails user,
            @PathVariable long orderId,
            @Valid @RequestBody CardOrderPaymentRequest request,
            HttpSession session
    ) {
        orderService.get(orderId, user.getCustomerId());
        cardCheckoutSessionService.requireMatches(orderId, request.currency(), request.amount(), session);
        var paymentSession = cardCheckoutSessionService.current(session);
        var paid = cardOrderPaymentService.pay(
                orderId,
                user.getCustomerId(),
                paymentSession.idempotencyKey(),
                request
        );
        if ("PAID".equals(paid.status())) {
            cardCheckoutSessionService.clear(session);
        }
        return paid;
    }

    @PostMapping("/{orderId}/card-checkout")
    OrderResponse cardCheckout(
            @AuthenticationPrincipal ShopUserDetails user,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @PathVariable long orderId,
            @Valid @RequestBody ExternalCardCheckoutRequest request
    ) {
        orderService.get(orderId, user.getCustomerId());
        return externalCardPaymentService.createCheckout(orderId, user.getCustomerId(), idempotencyKey, request);
    }
}
