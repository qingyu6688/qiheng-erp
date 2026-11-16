package cn.erp.shared;

/** 仅限服务器同线程的业务回调凭据，客户端不能通过请求字段构造。 */
public final class WorkflowContext implements AutoCloseable {
    private record Permit(
            long tenant, long company, long user, String type, long id, String priceFingerprint) {}

    private static final ThreadLocal<Permit> CURRENT = new ThreadLocal<>();
    private final Permit previous;

    private WorkflowContext(ActorScope actor, String type, long id, String fingerprint) {
        previous = CURRENT.get();
        CURRENT.set(
                new Permit(
                        actor.tenantId(),
                        actor.companyId(),
                        actor.userId(),
                        type,
                        id,
                        fingerprint));
    }

    public static WorkflowContext open(ActorScope actor, String type, long id) {
        return new WorkflowContext(actor, type, id, null);
    }

    public static WorkflowContext open(ActorScope actor, String type, long id, String fingerprint) {
        return new WorkflowContext(actor, type, id, fingerprint);
    }

    public static boolean allows(ActorScope actor, String type, long id) {
        var permit = CURRENT.get();
        return permit != null
                && permit.tenant() == actor.tenantId()
                && permit.company() == actor.companyId()
                && permit.user() == actor.userId()
                && permit.type().equals(type)
                && permit.id() == id;
    }

    public static boolean priceApproved(ActorScope actor, long id, String fingerprint) {
        return fingerprint != null
                && allows(actor, "sales_order", id)
                && fingerprint.equals(CURRENT.get().priceFingerprint());
    }

    @Override
    public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
