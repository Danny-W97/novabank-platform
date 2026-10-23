package org.demo.bankdemo.web;

import org.demo.bankdemo.currency.ExchangeException;
import org.demo.bankdemo.currency.ExchangeForm;
import org.demo.bankdemo.currency.ExchangeService;
import org.demo.bankdemo.currency.CurrencyCode;
import org.demo.bankdemo.dashboard.DashboardService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.UUID;

@Controller
@RequestMapping("/exchange")
public class ExchangeController {

    private final ExchangeService exchangeService;
    private final DashboardService dashboardService;

    public ExchangeController(ExchangeService exchangeService, DashboardService dashboardService) {
        this.exchangeService = exchangeService;
        this.dashboardService = dashboardService;
    }

    @GetMapping
    String exchange(Authentication authentication, Model model) {
        if (!model.containsAttribute("exchangeForm")) {
            var form = new ExchangeForm();
            form.setIdempotencyKey(UUID.randomUUID().toString());
            model.addAttribute("exchangeForm", form);
        }
        addCommon(authentication, model);
        model.addAttribute("currencies", CurrencyCode.values());
        model.addAttribute("balances", exchangeService.balancesFor(authentication.getName()));
        model.addAttribute("rates", exchangeService.rates());
        model.addAttribute("history", exchangeService.historyFor(authentication.getName()));
        return "exchange/index";
    }

    @PostMapping("/quote")
    String quote(
            Authentication authentication,
            @ModelAttribute("exchangeForm") ExchangeForm form,
            Model model
    ) {
        try {
            var prepared = exchangeService.prepareQuote(
                    authentication.getName(),
                    form.getSourceCurrency(),
                    form.getTargetCurrency(),
                    form.amountValue()
            );
            form.setIdempotencyKey(prepared.idempotencyKey());
            model.addAttribute("quote", prepared.quote());
            model.addAttribute("exchangeForm", form);
            addCommon(authentication, model);
            return "exchange/confirm";
        } catch (ExchangeException exception) {
            model.addAttribute("exchangeError", exception.getMessage());
            return exchange(authentication, model);
        }
    }

    @PostMapping("/execute")
    String execute(
            Authentication authentication,
            @ModelAttribute("exchangeForm") ExchangeForm form,
            Model model
    ) {
        try {
            exchangeService.executePrepared(authentication.getName(), form.getIdempotencyKey());
            return "redirect:/exchange?success";
        } catch (ExchangeException exception) {
            model.addAttribute("exchangeError", exception.getMessage());
            if (form.getIdempotencyKey() == null || form.getIdempotencyKey().isBlank()
                    || form.getIdempotencyKey().length() > 64) {
                return exchange(authentication, model);
            }
            var quote = exchangeService.pendingQuoteFor(authentication.getName(), form.getIdempotencyKey());
            if (quote.isEmpty()) return exchange(authentication, model);
            model.addAttribute("quote", quote.get());
            model.addAttribute("exchangeForm", form);
            addCommon(authentication, model);
            return "exchange/confirm";
        }
    }

    private void addCommon(Authentication authentication, Model model) {
        FrontendController.addCommonModel(
                model,
                dashboardService.dashboardFor(authentication.getName())
        );
    }
}
