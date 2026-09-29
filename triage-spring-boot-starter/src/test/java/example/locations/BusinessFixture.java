package example.locations;

public final class BusinessFixture {
    private BusinessFixture() {}
    public static void lookup(Runnable operation) { operation.run(); }
}
