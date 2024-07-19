package dynamic.mapping.rest;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import dynamic.mapping.configuration.ServiceConfiguration;
import dynamic.mapping.configuration.ServiceConfigurationComponent;
import dynamic.mapping.model.C8YNotificationSubscription;
import dynamic.mapping.model.Device;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.core.C8YAgent;
import dynamic.mapping.core.ConfigurationRegistry;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.validation.Valid;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@RestController
public class DeviceSubscriptionRestController {

    @Autowired
    C8YAgent c8yAgent;

    @Autowired
    private ContextService<UserCredentials> contextService;

    @Autowired
    private ConfigurationRegistry configurationRegistry;

    @Autowired
    ServiceConfigurationComponent serviceConfigurationComponent;

    @RequestMapping(value = "/subscription", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<C8YNotificationSubscription> subscriptionCreate(@Valid @RequestBody C8YNotificationSubscription subscription) {
        String tenant = contextService.getContext().getTenant();
        ServiceConfiguration serviceConfiguration = serviceConfigurationComponent.getServiceConfiguration(tenant);
        if (!serviceConfiguration.isOutboundMappingEnabled())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Output Mapping is disabled!");
        try {
            for (Device managedObject : subscription.getDevices()) {
                log.info("Tenant {} - Find all related Devices of Managed Object {}", managedObject.getId());
                ManagedObjectRepresentation mor = c8yAgent
                        .getManagedObjectForId(contextService.getContext().getTenant(), managedObject.getId());
                if (mor != null) {
                    List<Device> allChildDevices = configurationRegistry.getNotificationSubscriber().findAllRelatedDevicesByMO(mor, null);
                    for (Device device : allChildDevices) {
                        ManagedObjectRepresentation childDeviceMor = c8yAgent
                                .getManagedObjectForId(contextService.getContext().getTenant(), device.getId());
                        // Creates subscription for each connector
                        configurationRegistry.getNotificationSubscriber().subscribeDeviceAndConnect(childDeviceMor, subscription.getApi());
                    }
                    subscription.setDevices(allChildDevices);
                } else {
                    log.warn("Tenant {} - Could not subscribe device with id {}. Device does not exists!", tenant,
                            managedObject.getId());
                }
            }
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
        return ResponseEntity.ok(subscription);
    }

    @RequestMapping(value = "/subscription", method = RequestMethod.PUT, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<C8YNotificationSubscription> subscriptionUpdate(@Valid @RequestBody C8YNotificationSubscription subscription) {
        String tenant = contextService.getContext().getTenant();
        ServiceConfiguration serviceConfiguration = serviceConfigurationComponent.getServiceConfiguration(tenant);
        if (!serviceConfiguration.isOutboundMappingEnabled())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Output Mapping is disabled!");
        try {
            // List<NotificationSubscriptionRepresentation> deviceSubscriptions =
            // c8yApiSubscriber.getNotificationSubscriptions(null, null).get();
            C8YNotificationSubscription c8ySubscription = configurationRegistry.getNotificationSubscriber()
                    .getDeviceSubscriptions(tenant,
                            null, null);
            // 3 cases -
            // 1. Device exists in subscription and active subscription --> Do nothing
            // 2. Device exists in subscription and does not have an active subscription -->
            // create subscription
            // 3. Device exists not in subscription and does have an active subscription -->
            // delete subscription
            List<Device> toBeRemovedSub = new ArrayList<>();
            List<Device> toBeCreatedSub = new ArrayList<>();

            c8ySubscription.getDevices().forEach(device -> toBeRemovedSub.add(device));
            subscription.getDevices().forEach(device -> toBeCreatedSub.add(device));

            subscription.getDevices().forEach(device -> toBeRemovedSub.removeIf(x -> x.getId().equals(device.getId())));
            c8ySubscription.getDevices()
                    .forEach(entity -> toBeCreatedSub.removeIf(x -> x.getId().equals(entity.getId())));

            for (Device device : toBeCreatedSub) {
                ManagedObjectRepresentation mor = c8yAgent.getManagedObjectForId(tenant, device.getId());
                if (mor != null) {
                    try {
                        // Creates subscription for each connector
                        List<Device> allChildDevices = configurationRegistry.getNotificationSubscriber().findAllRelatedDevicesByMO(mor, null);
                        for (Device childDevice : allChildDevices) {
                            // Creates subscription for each connector
                            ManagedObjectRepresentation childDeviceMor = c8yAgent.getManagedObjectForId(tenant, childDevice.getId());
                            configurationRegistry.getNotificationSubscriber().subscribeDeviceAndConnect(childDeviceMor, subscription.getApi());
                        }
                        subscription.setDevices(allChildDevices);
                    } catch (Exception e) {
                        log.error("Tenant {} - Error creating subscriptions: ", tenant, e);
                        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
                    }
                } else {
                    log.warn("Tenant {} - Could not subscribe device with id {}. Device does not exists!", tenant,
                            device.getId());
                }
            }

            for (Device device : toBeRemovedSub) {
                ManagedObjectRepresentation mor = c8yAgent.getManagedObjectForId(tenant, device.getId());
                if (mor != null) {
                    try {
                        configurationRegistry.getNotificationSubscriber().unsubscribeDeviceAndDisconnect(mor);
                    } catch (Exception e) {
                        log.error("Tenant {} - Error removing subscriptions: ", tenant, e);
                        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
                    }
                } else {
                    log.warn("Tenant {} - Could not subscribe device with id {}. Device does not exists!", tenant,
                            device.getId());
                }
            }

        } catch (Exception e) {
            log.error("Tenant {} - Error updating subscriptions: ", tenant, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
        return ResponseEntity.ok(subscription);
    }

    @RequestMapping(value = "/subscriptions", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<C8YNotificationSubscription> subscriptionsGet(@RequestParam(required = false) String deviceId,
            @RequestParam(required = false) String subscriptionName) {
        String tenant = contextService.getContext().getTenant();
        ServiceConfiguration serviceConfiguration = serviceConfigurationComponent.getServiceConfiguration(tenant);
        if (!serviceConfiguration.isOutboundMappingEnabled())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Output Mapping is disabled!");
        try {
            C8YNotificationSubscription c8YAPISubscription = configurationRegistry.getNotificationSubscriber()
                    .getDeviceSubscriptions(contextService.getContext().getTenant(), deviceId, subscriptionName);
            return ResponseEntity.ok(c8YAPISubscription);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
    }

    @RequestMapping(value = "/subscription/{deviceId}", method = RequestMethod.DELETE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> subscriptionDelete(@PathVariable String deviceId) {
        String tenant = contextService.getContext().getTenant();
        ServiceConfiguration serviceConfiguration = serviceConfigurationComponent.getServiceConfiguration(tenant);
        if (!serviceConfiguration.isOutboundMappingEnabled())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Output Mapping is disabled!");
        try {
            ManagedObjectRepresentation mor = c8yAgent.getManagedObjectForId(contextService.getContext().getTenant(),
                    deviceId);
            if (mor != null) {
                configurationRegistry.getNotificationSubscriber().unsubscribeDeviceAndDisconnect(mor);
            } else {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Could not delete subscription for device with id " + deviceId + ". Device not found");
            }
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
        return ResponseEntity.ok().build();
    }
}
