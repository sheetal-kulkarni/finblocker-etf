package com.cts.api;

import com.cts.flow.ETFundInceptionFlow;
import com.cts.flow.ETFundExersizingFlow;
import com.cts.state.ETFundState;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import net.corda.core.contracts.StateAndRef;
import net.corda.core.identity.CordaX500Name;
import net.corda.core.identity.Party;
import net.corda.core.messaging.CordaRPCOps;
import net.corda.core.messaging.FlowProgressHandle;
import net.corda.core.node.NodeInfo;
import net.corda.core.transactions.SignedTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static java.util.stream.Collectors.toList;
import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static javax.ws.rs.core.Response.Status.CREATED;

// This API is accessible from /api/example. All paths specified below are relative to it.
@Path("ETF")
public class ETFundEndPoint {
    private final CordaRPCOps rpcOps;
    private final CordaX500Name myLegalName;

    private final List<String> serviceNames = ImmutableList.of("Controller", "Network Map Service");

    static private final Logger logger = LoggerFactory.getLogger(ETFApi.class);

    public ETFApi(CordaRPCOps rpcOps) {
        this.rpcOps = rpcOps;
        this.myLegalName = rpcOps.nodeInfo().getLegalIdentities().get(0).getName();
    }

    /**
     * Returns the node's name.
     */
    @GET
    @Path("me")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, CordaX500Name> whoami() {
        return ImmutableMap.of("me", myLegalName);
    }

    @POST
    @Path("inception")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createProduct(ETFund etfund) {

        System.out.println("Received ETFund " + etfund);

        String otherPartyName = "AP";
        if (myLegalName.equals("AP")) {
            otherPartyName = "ETFSponsor";
        }

        System.out.println("After getting Node Name " + etfund);
        final Party otherParty = services.partyFromName(otherPartyName);
        final Party buyer = services.partyFromName(etfund.getBuyer());
        final Party seller = services.partyFromName(etfund.getSeller());
        System.out.println("After creating Node Objects");
        final ETFundState state = new ETFundState(
                etfund,
                buyer,
                seller,
                ETFundStateStatus.INCEPTION,
                new ETFundContract());

        System.out.println("After creating ETFundState");
        Response.Status status;
        status = Response.Status.CREATED;
        String msg = "Done ";
        try {
            FlowProgressHandle<SignedTransaction> flowHandle = services
                    .startTrackedFlowDynamic(ETFundInceptionFlow.Initiator.class, state, otherParty);
            flowHandle.getProgress().subscribe(evt -> System.out.printf(">> %s\n", evt));

            // The line below blocks and waits for the flow to return.
            final SignedTransaction result = flowHandle
                    .getReturnValue()
                    .get();

            status = Response.Status.CREATED;
            msg = String.format("Transaction id %s committed to ledger.", result.getId());

        } catch (Throwable ex) {
            status = Response.Status.BAD_REQUEST;
            msg = "Transaction failed.";
            ex.printStackTrace();
            logger.error(ex.getMessage(), ex);
        }

        return Response
                .status(status)
                .entity(msg)
                .build();
    }


    @GET
    @Path("trigger-exercising")
    public Response triggerExercisingFlow(@Context UriInfo info) {

        System.out.println("Inside triggerExercisingFlow WS Call");

        String otherPartyName = "AP";
        if (myLegalName.equals("AP")) {
            otherPartyName = "ETFSponsor";
        }

        final Party otherParty = services.partyFromName(otherPartyName);

        String refId = null, etfRate = null;
        if (info.getQueryParameters() != null && info.getQueryParameters().size() > 0) {
            refId = info.getQueryParameters().getFirst("refid");
            etfRate = info.getQueryParameters().getFirst("etfrate");
        }
        System.out.println("Extracted FxRate: " + fxRate + "and RefID: " + refId);
        Response.Status status;
        status = Response.Status.CREATED;
        String msg = "Done ";
        try {
            FlowProgressHandle<SignedTransaction> flowHandle = services
                    .startTrackedFlowDynamic(ETFundExersizingFlow.Initiator.class, otherParty, refId, new Float(etfRate));
            flowHandle.getProgress().subscribe(evt -> System.out.printf(">> %s\n", evt));

            // The line below blocks and waits for the flow to return.
            SignedTransaction result = flowHandle
                    .getReturnValue()
                    .get();


            flowHandle = services
                    .startTrackedFlowDynamic(ETFundBookingFlow.Initiator.class, otherParty, refId, new Float(fxRate));
            flowHandle.getProgress().subscribe(evt -> System.out.printf(">> %s\n", evt));

            // The line below blocks and waits for the flow to return.
            result = flowHandle
                    .getReturnValue()
                    .get();


            status = Response.Status.CREATED;
            msg = "Transactions committed to ledger.\n ETF Trade Booking complete";

        } catch (Throwable ex) {
            status = Response.Status.BAD_REQUEST;
            msg = "Transaction failed.";
            logger.error(ex.getMessage(), ex);
        }

        return Response
                .status(status)
                .entity(msg)
                .build();
    }


}