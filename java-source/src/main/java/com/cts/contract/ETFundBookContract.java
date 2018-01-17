package com.cts.contract;

import com.cts.state.ETFBookState;
import net.corda.core.contracts.CommandData;
import net.corda.core.contracts.CommandWithParties;
import net.corda.core.contracts.Contract;
import net.corda.core.identity.AbstractParty;
import net.corda.core.transactions.LedgerTransaction;

import java.util.stream.Collectors;

import static net.corda.core.contracts.ContractsDSL.requireSingleCommand;
import static net.corda.core.contracts.ContractsDSL.requireThat;

/**
 * A implementation of a basic smart contract in Corda.
 *
 * This contract enforces rules regarding the creation of a valid [IOUState], which in turn encapsulates an [IOU].
 *
 * For a new [IOU] to be issued onto the ledger, a transaction is required which takes:
 * - Zero input states.
 * - One output state: the new [IOU].
 * - An Create() command with the public keys of both the lender and the borrower.
 *
 * All contracts must sub-class the [Contract] interface.
 */
public class ETFundBookContract implements Contract {
    public static final String ETF_CONTRACT_ID = "com.cts.contract.ETFundBookContract";



    private final SecureHash legalContractReference = SecureHash.sha256("ETFund Spot Trade Contract template and params");

    @Override
    public final SecureHash getLegalContractReference() {
        return legalContractReference;
    }

    @Override
    public void verify(TransactionForContract tx) {
        System.out.println("Inside Verify: " + extractCommands(tx));
        ClauseVerifier.verifyClause(
                tx,
                new AllComposition<>(new Clauses.Group()),
                extractCommands(tx));
    }

    private List<AuthenticatedObject<Commands>> extractCommands(TransactionForContract tx) {
        return tx.getCommands()
                .stream()
                .filter(command -> command.getValue() instanceof Commands)
                .map(command -> new AuthenticatedObject<>(
                        command.getSigners(),
                        command.getSigningParties(),
                        (Commands) command.getValue()))
                .collect(toList());
    }


    public interface Commands extends CommandData {
        class Booking implements IssueCommand, Commands {
            private final long nonce = Utils.random63BitValue();

            @Override
            public long getNonce() {
                return nonce;
            }
        }

        class Settlement implements IssueCommand, Commands {
            private final long nonce = Utils.random63BitValue();

            @Override
            public long getNonce() {
                return nonce;
            }
        }
    }

    public interface Clauses {
        /**
         * Checks for the existence of a timestamp.
         */
        class Timestamp extends Clause<ContractState, Commands, Unit> {
            @Override
            public Set<Commands> verify(TransactionForContract tx,
                                        List<? extends ContractState> inputs,
                                        List<? extends ContractState> outputs,
                                        List<? extends AuthenticatedObject<? extends Commands>> commands,
                                        Unit groupingKey) {
                System.out.println("Inside Timestap verify Clause");
                //  requireNonNull(tx.getTimestamp(), "must be timestamped");
                System.out.println("Completed Clause Timestamp");
                // We return an empty set because we don't process any commands
                return Collections.emptySet();
            }
        }

        // If you add additional clauses, make sure to reference them within the 'AnyComposition()' clause.
        class Group extends GroupClauseVerifier<FXTradeState, Commands, UniqueIdentifier> {
            // public Group() { super(new AnyComposition<>(new Clauses.Book())); }
            public Group() {
                super(new AnyComposition<>(new Clauses.Booking(), new Clauses.Settlement()));
            }

            @Override
            public List<InOutGroup<FXTradeState, UniqueIdentifier>> groupStates(TransactionForContract tx) {
                System.out.println("Completed Clause Group");
                // Group by Intercompany  state linearId for in/out states.
                return tx.groupStates(FXTradeState.class, FXTradeState::getLinearId);
            }
        }

        /**
         * Checks various requirements for the Booking of a Intercompany Trade.
         */
        class Booking extends Clause<FXTradeState, Commands, UniqueIdentifier> {
            @Override
            public Set<Class<? extends CommandData>> getRequiredCommands() {
                return Collections.singleton(Commands.Booking.class);
            }

            @Override
            public Set<Commands> verify(TransactionForContract tx,
                                        List<? extends FXTradeState> inputs,
                                        List<? extends FXTradeState> outputs,
                                        List<? extends AuthenticatedObject<? extends Commands>> commands,
                                        UniqueIdentifier groupingKey) {
                System.out.println("Inside Booking clause: commands " + tx.getCommands());
                final AuthenticatedObject<Commands.Booking> command = requireSingleCommand(tx.getCommands(), Commands.Booking.class);
                FXTradeState out = outputs.get(0);
                System.out.println("Extracted output for verification in Booking clause" + out);
                //TODO:: Create correct verify conditions for the contract. currently only very few validations are added.
                requireThat(require -> {
                    // Generic constraints around generation of the issue purchase order transaction.
                    require.by("The buyer and the seller cannot be the same entity.",
                            out.getBuyer() != out.getSeller());

                    require.by("The FX Rate cannot be zero.",
                            out.getFxTrade().getFxRate() != 0);

                    require.by("The FX Trade should be associated with a fX Product.",
                            out.getFxTrade().getStructuredProductReferenceId() != null);
                    return null;
                });
                return Collections.singleton(command.getValue());
            }
        }

        /**
         * Checks various requirements for the Matching of a Intercompany Trade.
         */
        class Settlement extends Clause<FXTradeState, Commands, UniqueIdentifier> {
            @Override
            public Set<Class<? extends CommandData>> getRequiredCommands() {
                return Collections.singleton(Commands.Settlement.class);
            }

            @Override
            public Set<Commands> verify(TransactionForContract tx,
                                        List<? extends FXTradeState> inputs,
                                        List<? extends FXTradeState> outputs,
                                        List<? extends AuthenticatedObject<? extends Commands>> commands,
                                        UniqueIdentifier groupingKey) {
                final AuthenticatedObject<Commands.Settlement> command = requireSingleCommand(tx.getCommands(), Commands.Settlement.class);
                requireThat(require -> {
                    require.by("There should be one output state.",
                            outputs != null && outputs.size() == 1);
                    require.by("There should be one input state.",
                            inputs != null && inputs.size() == 1);
                    require.by("The buyer and the seller for the input and output should be same.",
                            outputs.get(0).getBuyer().equals(inputs.get(0).getBuyer()) &&
                                    outputs.get(0).getSeller().equals(inputs.get(0).getSeller())
                    );

                    return null;
                });
                return Collections.singleton(command.getValue());
            }
        }
    }
}

/**
     * This contract only implements one command, Create.
     */
    public interface Commands extends CommandData {
        class Create implements Commands {}
    }
}