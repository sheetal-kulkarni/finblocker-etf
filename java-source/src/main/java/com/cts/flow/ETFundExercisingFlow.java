package com.cts.flow;

import co.paralleluniverse.fibers.Suspendable;
import com.cts.contract.ETFundContract;
import com.cts.state.ETFundStateStatus;
//import com.cts.bfs.cordapp.fxproduct.util.StructuredProductsHelper;
import com.cts.state.ETFundState;
import com.cts.bfs.cordapp.fxproduct.vault.VaultManager;
import com.google.common.collect.ImmutableSet;
import net.corda.core.contracts.Command;
import net.corda.core.contracts.StateAndRef;
import net.corda.core.contracts.TransactionType;
import net.corda.core.crypto.DigitalSignature;
import net.corda.core.crypto.Party;
import net.corda.core.flows.FlowException;
import net.corda.core.flows.FlowLogic;
import net.corda.core.transactions.SignedTransaction;
import net.corda.core.transactions.TransactionBuilder;
import net.corda.core.transactions.WireTransaction;
import net.corda.core.utilities.ProgressTracker;
import net.corda.flows.FinalityFlow;

import java.security.KeyPair;
import java.security.PublicKey;
import java.security.SignatureException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * This flow allows two parties (the [Initiator] and the [Acceptor]) to come to an agreement about the IOU encapsulated
 * within an [IOUState].
 * <p>
 * In our simple example, the [Acceptor] always accepts a valid IOU.
 * <p>
 * These flows have deliberately been implemented by using only the call() method for ease of understanding. In
 * practice we would recommend splitting up the various stages of the flow into sub-routines.
 * <p>
 * All methods called within the [FlowLogic] sub-class need to be annotated with the @Suspendable annotation.
 */
public class ETFundExercisingFlow {
    public static class Initiator extends FlowLogic<SignedTransaction> {

        // private final IOUState iou;
        private final String etfundRefId;
        private final float etfRate;
        private final Party otherParty;

        // The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
        // checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call()
        // function.
        private final ProgressTracker progressTracker = new ProgressTracker(

                EXTRACTING_SP,
                GENERATING_TRANSACTION,
                VERIFYING_TRANSACTION,
                SIGNING_TRANSACTION,
                SENDING_TRANSACTION
        );

        private static final ProgressTracker.Step EXTRACTING_SP = new ProgressTracker.Step(
                "Extracting Structured Product Details from vault.");
        private static final ProgressTracker.Step GENERATING_TRANSACTION = new ProgressTracker.Step(
                "Generating Exercising transaction and states.");
        private static final ProgressTracker.Step VERIFYING_TRANSACTION = new ProgressTracker.Step(
                "Verifying Inception contract constraints.");
        private static final ProgressTracker.Step SIGNING_TRANSACTION = new ProgressTracker.Step(
                "Signing transaction with our private key.");
        private static final ProgressTracker.Step SENDING_TRANSACTION = new ProgressTracker.Step(
                "Sending proposed transaction to CounterParty for review.");

        public Initiator(Party otherParty, String etfundRefId, float etfRate) {
            this.etfundRefId = etfundRefId;
            this.etfRate = etfRate;
            this.otherParty = otherParty;
        }

        @Override
        public ProgressTracker getProgressTracker() {
            return progressTracker;
        }

        /**
         * The flow logic is encapsulated within the call() method.
         */
        @Suspendable
        @Override
        public SignedTransaction call() throws FlowException {
            // Prep.
            // Obtain a reference to our key pair. Currently, the only key pair used is the one which is registered with
            // the NetWorkMapService. In a future milestone release we'll implement HD key generation such that new keys
            // can be generated for each transaction.
            final KeyPair keyPair = getServiceHub().getLegalIdentityKey();
            // Obtain a reference to the notary we want to use.
            final Party notary = getServiceHub().getNetworkMapCache().getNotaryNodes().get(0).getNotaryIdentity();

            // Stage 1.
            progressTracker.setCurrentStep(EXTRACTING_SP);

            VaultManager vaultManager = new VaultManager(getServiceHub().getVaultService());

            StateAndRef<ETFundState> inputStateAndRef = vaultManager.getETFundStateFromReference(etfundRefId);
            ETFundState  inputState , outputState = null;

            if (inputStateAndRef == null) {
                throw new RuntimeException("No Input state found for Reference ID passed in");
            }
            try {
                inputState = inputStateAndRef.getState().component1();
                outputState = inputState.clone();
                //set rest of the output state fields
                outputState.setEtfRate(etfRate);
                outputState.setIterationNo(inputState.getIterationNo() + 1);
                outputState.setStatus(ETFundStateStatus.EXERCISING);
//                float tradeValue = StructuredProductsHelper.getTradeValueBasedOnFXRate(outputState.getStructuredProduct() , fxRate);
//                float oldMaxExposure = outputState.getStructuredProduct().getMaxExposure();
                outputState.getStructuredProduct().setMaxExposure(oldMaxExposure - tradeValue);
            } catch (CloneNotSupportedException e) {
                throw new RuntimeException("Error while cloning input state");
            }

            // Stage 1.
            progressTracker.setCurrentStep(GENERATING_TRANSACTION);
            // Generate an unsigned transaction.
            final Command txCommand = new Command(new ETFundContract.Commands.Exercise(), outputState.getParticipants());
            final TransactionBuilder unsignedTx = new TransactionType.General.Builder(notary).
                    withItems(inputStateAndRef , outputState, txCommand);


            unsignedTx.setTime(Instant.now(), Duration.ofSeconds(60));
            // Stage 2.
            progressTracker.setCurrentStep(VERIFYING_TRANSACTION);
            // Verify that the transaction is valid.
            unsignedTx.toWireTransaction().toLedgerTransaction(getServiceHub()).verify();

            // Stage 3.
            progressTracker.setCurrentStep(SIGNING_TRANSACTION);
            final SignedTransaction partSignedTx = unsignedTx.signWith(keyPair).toSignedTransaction(false);

            // Stage 4.
            progressTracker.setCurrentStep(SENDING_TRANSACTION);
            // Send the state across the wire to the designated counterparty.
            // -----------------------
            // Flow jumps to Acceptor.
            // -----------------------
            this.send(otherParty, partSignedTx);

            return waitForLedgerCommit(partSignedTx.getId());
        }


    }

    public static class Acceptor extends FlowLogic<Void> {

        private final Party otherParty;
        private final ProgressTracker progressTracker = new ProgressTracker(
                RECEIVING_TRANSACTION,
                VERIFYING_TRANSACTION,
                SIGNING_TRANSACTION,
                FINALISING_TRANSACTION
        );

        private static final ProgressTracker.Step RECEIVING_TRANSACTION = new ProgressTracker.Step(
                "Receiving proposed transaction from Bank.");
        private static final ProgressTracker.Step VERIFYING_TRANSACTION = new ProgressTracker.Step(
                "Verifying signatures and contract constraints.");
        private static final ProgressTracker.Step SIGNING_TRANSACTION = new ProgressTracker.Step(
                "Signing proposed transaction with our private key.");
        private static final ProgressTracker.Step FINALISING_TRANSACTION = new ProgressTracker.Step(
                "Obtaining notary signature and recording transaction.");

        public Acceptor(Party otherParty) {
            this.otherParty = otherParty;
        }

        @Override
        public ProgressTracker getProgressTracker() {
            return progressTracker;
        }

        @Suspendable
        @Override
        public Void call() throws FlowException {
            // Prep.
            // Obtain a reference to our key pair.
            final KeyPair keyPair = getServiceHub().getLegalIdentityKey();
            final Party notary = getServiceHub().getNetworkMapCache().getNotaryNodes().get(0).getNotaryIdentity();
            // Obtain a reference to the notary we want to use and its public key.
            final PublicKey notaryPubKey = notary.getOwningKey();

            // Stage 5.
            progressTracker.setCurrentStep(RECEIVING_TRANSACTION);
            // All messages come off the wire as UntrustworthyData. You need to 'unwrap' them. This is where you
            // validate what you have just received.

            final SignedTransaction partSignedTx = receive(SignedTransaction.class, otherParty)
                    .unwrap(tx ->
                    {
                        // Stage 6.
                        progressTracker.setCurrentStep(VERIFYING_TRANSACTION);
                        try {
                            // Check that the signature of the other party is valid.
                            // Our signature and the notary's signature are allowed to be omitted at this stage as
                            // this is only a partially signed transaction.
                            final WireTransaction wireTx = tx.verifySignatures(keyPair.getPublic(), notaryPubKey);

                            // Run the contract's verify function.
                            // We want to be sure that the agreed-upon IOU is valid under the rules of the contract.
                            // To do this we need to run the contract's verify() function.
                            wireTx.toLedgerTransaction(getServiceHub()).verify();
                        } catch (SignatureException ex) {
                            throw new FlowException(tx.getId() + " failed signature checks", ex);
                        }
                        return tx;
                    });

            System.out.println("Before Signing Transaction: ");
            // Stage 7.
            progressTracker.setCurrentStep(SIGNING_TRANSACTION);
            // Sign the transaction with our key pair and add it to the transaction.
            // We now have 'validation consensus'. We still require uniqueness consensus.
            // Technically validation consensus for this type of agreement implicitly provides uniqueness consensus.
            final DigitalSignature.WithKey mySig = partSignedTx.signWithECDSA(keyPair);
            // Add our signature to the transaction.
            final SignedTransaction signedTx = partSignedTx.plus(mySig);
            System.out.println("Transaction Signed: ");
            // Stage 8.
            progressTracker.setCurrentStep(FINALISING_TRANSACTION);
            final Set<Party> participants = ImmutableSet.of(getServiceHub().getMyInfo().getLegalIdentity(), otherParty);
            System.out.println("Participants: "+ participants);
            // FinalityFlow() notarises the transaction and records it in each party's vault.
            subFlow(new FinalityFlow(signedTx, participants));

            return null;
        }
    }


}