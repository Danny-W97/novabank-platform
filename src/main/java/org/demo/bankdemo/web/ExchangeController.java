package org.demo.bankdemo.web;

import jakarta.servlet.http.HttpSession;
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

    private static final String QUOTE_ATTRIBUTE = "BANK_EXCHANGE_QUOTE";

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
            HttpSession session,
            Model model
    ) {
        try {
            var quote = exchangeService.quote(
                    form.getSourceCurrency(),
                    form.getTargetCurrency(),
                    form.amountValue()
            );
            session.setAttribute(QUOTE_ATTRIBUTE, quote);
            model.addAttribute("quote", quote);
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
            HttpSession session,
            Model model
    ) {
        var quote = session.getAttribute(QUOTE_ATTRIBUTE);
        if (!(quote instanceof ExchangeService.Quote exchangeQuote)) {
            return "redirect:/exchange?error=expired";
        }
        try {
            exchangeService.execute(authentication.getName(), exchangeQuote, form.getIdempotencyKey());
            session.removeAttribute(QUOTE_ATTRIBUTE);
            return "redirect:/exchange?success";
        } catch (ExchangeException exception) {
            model.addAttribute("exchangeError", exception.getMessage());
            model.addAttribute("quote", exchangeQuote);
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
