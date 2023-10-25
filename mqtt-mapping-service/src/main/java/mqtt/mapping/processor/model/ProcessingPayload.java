package mqtt.mapping.processor.model;

public interface ProcessingPayload<P> {
    public void setPayload(P p);

    public P getPayload();
}
