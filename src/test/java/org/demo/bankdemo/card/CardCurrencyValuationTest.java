package org.demo.bankdemo.card;
import org.demo.bankdemo.currency.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CardCurrencyValuationTest {
    @Test void missingRateCannotBypassForeignCurrencyLimit() {
        var repository = mock(ExchangeRateRepository.class);
        when(repository.findById(CurrencyCode.EUR)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> new CardCurrencyValuation(repository).inPln(CurrencyCode.EUR, BigDecimal.TEN))
                .isInstanceOf(CardException.class);
        assertThat(new CardCurrencyValuation(repository).inPln(CurrencyCode.PLN, BigDecimal.TEN)).isEqualByComparingTo("10.00");
        assertThatThrownBy(() -> new CardCurrencyValuation(null)).isInstanceOf(NullPointerException.class);
    }
}
