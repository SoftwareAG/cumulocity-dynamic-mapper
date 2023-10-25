package mqtt.mapping.processor.model;

public class GenericProcessingPayload<P> implements ProcessingPayload<P> {

    private P payload;

    public GenericProcessingPayload(P payload) {
        this.payload = payload;
    }

    @Override
    public void setPayload(P p) {
        this.payload = p;
    }

    @Override
    public P getPayload() {
        return this.payload;
    }

}