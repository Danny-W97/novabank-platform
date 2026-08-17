package org.demo.shopapp.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefundRequest(
        @NotBlank @Size(max = 140) String reason
) {
}
