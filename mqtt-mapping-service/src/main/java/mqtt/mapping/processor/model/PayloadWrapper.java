package mqtt.mapping.processor.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
public class PayloadWrapper {

    @Getter
    private String message;

}