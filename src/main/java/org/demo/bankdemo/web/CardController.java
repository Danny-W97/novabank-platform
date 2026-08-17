package org.demo.bankdemo.web;

import org.demo.bankdemo.card.CardException;
import org.demo.bankdemo.card.CardForm;
import org.demo.bankdemo.card.CardService;
import org.demo.bankdemo.currency.CurrencyCode;
import org.demo.bankdemo.dashboard.DashboardService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

@Controller
@RequestMapping("/card")
public class CardController {

    private final CardService cardService;
    private final DashboardService dashboardService;

    public CardController(CardService cardService, DashboardService dashboardService) {
        this.cardService = cardService;
        this.dashboardService = dashboardService;
    }

    @GetMapping
    String card(Authentication authentication, Model model) {
        prepare(authentication, model);
        return "card/index";
    }

    @PostMapping("/attach")
    String attach(Authentication authentication) {
        cardService.attach(authentication.getName());
        return "redirect:/card";
    }

    @PostMapping("/pay")
    String pay(
            Authentication authentication,
            @ModelAttribute("cardForm") CardForm form,
            Model model
    ) {
        try {
            cardService.pay(
                    authentication.getName(),
                    form.getMerchantName(),
                    form.getCurrency(),
                    form.amountValue(),
                    form.getChannel(),
                    form.getIdempotencyKey()
            );
            return "redirect:/card?success";
        } catch (CardException exception) {
            model.addAttribute("cardError", exception.getMessage());
            prepare(authentication, model);
            return "card/index";
        }
    }

    @PostMapping("/block")
    String block(Authentication authentication) {
        cardService.block(authentication.getName());
        return "redirect:/card";
    }

    @PostMapping("/activate")
    String activate(Authentication authentication) {
        cardService.activate(authentication.getName());
        return "redirect:/card";
    }

    @PostMapping("/block-temporary")
    String blockTemporarily(
            Authentication authentication,
            @RequestParam(defaultValue = "30") int minutes
    ) {
        cardService.blockTemporarily(authentication.getName(), minutes);
        return "redirect:/card";
    }

    private void prepare(Authentication authentication, Model model) {
        if (!model.containsAttribute("cardForm")) {
            var form = new CardForm();
            form.setIdempotencyKey(UUID.randomUUID().toString());
            model.addAttribute("cardForm", form);
        }
        model.addAttribute("card", cardService.cardFor(authentication.getName()));
        model.addAttribute("currencies", CurrencyCode.values());
        FrontendController.addCommonModel(
                model,
                dashboardService.dashboardFor(authentication.getName())
        );
    }
}
