package mqtt.mapping.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionAddedEvent;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.rest.representation.measurement.MeasurementRepresentation;
import com.cumulocity.sdk.client.measurement.MeasurementApi;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class SimpleJsonDispatcher {
	
    @Autowired
    private MicroserviceSubscriptionsService subscriptionsService;
	
	@Autowired
	private ObjectMapper objectMapper;
	
	@Autowired
	private MeasurementApi measurementApi;

	private String tenant;
	
	
    @EventListener
    public void initialize(MicroserviceSubscriptionAddedEvent event) {
        tenant = event.getCredentials().getTenant();
    }
	
	public void createMeasurement(String json) {
		try {
			MeasurementRepresentation measurement = objectMapper.readValue(json, MeasurementRepresentation.class);
			subscriptionsService.callForTenant(tenant, ()->measurementApi.create(measurement));
			log.info("successfully created measurement: {}", measurement);
		} catch (Exception e) {
			log.error("error!", e);
		}
	}

}
