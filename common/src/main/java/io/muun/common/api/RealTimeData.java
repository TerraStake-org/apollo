package io.muun.common.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import javax.validation.constraints.NotNull;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class RealTimeData {

    @NotNull
    public FeeWindowJson feeWindow;

    @NotNull
    public ExchangeRateWindow exchangeRateWindow;

    /**
     * Json constructor.
     */
    public RealTimeData() {
    }

    /**
     * Houston constructor.
     */
    public RealTimeData(FeeWindowJson feeWindow, ExchangeRateWindow exchangeRateWindow) {
        this.feeWindow = feeWindow;
        this.exchangeRateWindow = exchangeRateWindow;
    }
}
