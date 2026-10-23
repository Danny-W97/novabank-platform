package org.demo.bankdemo.web;

import jakarta.validation.Valid;
import org.demo.bankdemo.dashboard.DashboardService;
import org.demo.bankdemo.recipient.RecipientException;
import org.demo.bankdemo.recipient.RecipientForm;
import org.demo.bankdemo.recipient.RecipientService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/recipients")
public class RecipientController {

    private final RecipientService recipientService;
    private final DashboardService dashboardService;

    public RecipientController(
            RecipientService recipientService,
            DashboardService dashboardService
    ) {
        this.recipientService = recipientService;
        this.dashboardService = dashboardService;
    }

    @GetMapping
    String list(Authentication authentication, Model model) {
        model.addAttribute("recipients", recipientService.recipientsFor(authentication.getName()));
        addCommon(authentication, model);
        return "recipients/list";
    }

    @GetMapping("/new")
    String createForm(Authentication authentication, Model model) {
        if (!model.containsAttribute("recipientForm")) {
            model.addAttribute("recipientForm", new RecipientForm());
        }
        model.addAttribute("formAction", "/recipients");
        model.addAttribute("formTitle", "Nowy odbiorca");
        addCommon(authentication, model);
        return "recipients/form";
    }

    @PostMapping
    String create(
            Authentication authentication,
            @Valid @ModelAttribute RecipientForm recipientForm,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        if (!bindingResult.hasErrors()) {
            try {
                recipientService.create(authentication.getName(), recipientForm);
            } catch (RecipientException exception) {
                reject(bindingResult, exception);
            }
        }
        if (bindingResult.hasErrors()) {
            model.addAttribute("formAction", "/recipients");
            model.addAttribute("formTitle", "Nowy odbiorca");
            addCommon(authentication, model);
            return "recipients/form";
        }
        redirectAttributes.addFlashAttribute("recipientSuccess", "Odbiorca został zapisany.");
        return "redirect:/recipients";
    }

    @GetMapping("/{id}/edit")
    String editForm(@PathVariable Long id, Authentication authentication, Model model) {
        model.addAttribute("recipientForm", recipientService.formFor(authentication.getName(), id));
        model.addAttribute("formAction", "/recipients/" + id);
        model.addAttribute("formTitle", "Edytuj odbiorcę");
        addCommon(authentication, model);
        return "recipients/form";
    }

    @PostMapping("/{id}")
    String update(
            @PathVariable Long id,
            Authentication authentication,
            @Valid @ModelAttribute RecipientForm recipientForm,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        if (!bindingResult.hasErrors()) {
            try {
                recipientService.update(authentication.getName(), id, recipientForm);
            } catch (org.demo.bankdemo.recipient.RecipientNotFoundException missing) {
                throw missing;
            } catch (RecipientException exception) {
                reject(bindingResult, exception);
            }
        }
        if (bindingResult.hasErrors()) {
            model.addAttribute("formAction", "/recipients/" + id);
            model.addAttribute("formTitle", "Edytuj odbiorcę");
            addCommon(authentication, model);
            return "recipients/form";
        }
        redirectAttributes.addFlashAttribute("recipientSuccess", "Dane odbiorcy zostały zmienione.");
        return "redirect:/recipients";
    }

    @PostMapping("/{id}/delete")
    String delete(
            @PathVariable Long id,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        recipientService.delete(authentication.getName(), id);
        redirectAttributes.addFlashAttribute("recipientSuccess", "Odbiorca został usunięty.");
        return "redirect:/recipients";
    }

    private void addCommon(Authentication authentication, Model model) {
        FrontendController.addCommonModel(
                model,
                dashboardService.dashboardFor(authentication.getName())
        );
    }

    private static void reject(BindingResult result, RecipientException exception) {
        if (exception.getField() == null) {
            result.reject("recipient.invalid", exception.getMessage());
        } else {
            result.rejectValue(exception.getField(), "recipient.invalid", exception.getMessage());
        }
    }
}
