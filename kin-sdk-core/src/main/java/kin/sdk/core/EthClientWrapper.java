package kin.sdk.core;

import java.io.File;
import java.math.BigDecimal;
import kin.sdk.core.exception.EthereumClientException;
import kin.sdk.core.exception.InsufficientBalanceException;
import kin.sdk.core.exception.OperationFailedException;
import kin.sdk.core.exception.PassphraseException;
import kin.sdk.core.util.KinConverter;
import org.ethereum.geth.Account;
import org.ethereum.geth.Address;
import org.ethereum.geth.BigInt;
import org.ethereum.geth.BoundContract;
import org.ethereum.geth.CallOpts;
import org.ethereum.geth.Context;
import org.ethereum.geth.EthereumClient;
import org.ethereum.geth.Geth;
import org.ethereum.geth.Interface;
import org.ethereum.geth.Interfaces;
import org.ethereum.geth.KeyStore;
import org.ethereum.geth.TransactOpts;
import org.ethereum.geth.Transaction;

/**
 * A Wrapper to the geth (go ethereum) library.
 * Responsible for account creation/storage/retrieval, connection to Kin contract
 * retrieving balance and sending transactions
 */
final class EthClientWrapper {

    private Context gethContext;
    private EthereumClient ethereumClient;
    private BoundContract boundContract;
    private KeyStore keyStore;
    private ServiceProvider serviceProvider;
    private final String kinContractAddress;
    private long nonce = -1;
    private BigInt gasPrice = null;
    private final PendingBalance pendingBalance;

    EthClientWrapper(android.content.Context androidContext, ServiceProvider serviceProvider)
        throws EthereumClientException {
        this.serviceProvider = serviceProvider;
        this.gethContext = new Context();
        this.kinContractAddress = getContractAddress();
        initEthereumClient();
        initKinContract();
        initKeyStore(androidContext);
        this.pendingBalance = new PendingBalance(ethereumClient, gethContext, kinContractAddress);
    }

    /**
     * Create {@link EthereumClient}, that will be a connection to Ethereum network.
     *
     * @throws EthereumClientException if go-ethereum could not establish connection to the provider.
     */
    private void initEthereumClient() throws EthereumClientException {
        try {
            this.ethereumClient = Geth.newEthereumClient(serviceProvider.getProviderUrl());
        } catch (Exception e) {
            throw new EthereumClientException("provider - could not establish connection to the provider");
        }
    }

    /**
     * Create {@link BoundContract}, that will handle all the calls to Kin smart-contract.
     *
     * @throws EthereumClientException if go-ethereum could not establish connection to Kin smart-contract.
     */
    private void initKinContract() throws EthereumClientException {
        try {
            Address contractAddress = Geth.newAddressFromHex(kinContractAddress);
            this.boundContract = Geth.bindContract(contractAddress, KinConsts.ABI, ethereumClient);
        } catch (Exception e) {
            throw new EthereumClientException("contract - could not establish connection to Kin smart-contract");
        }
    }

    /**
     * Create {@link KeyStore}, to have control over the account management.
     * And the ability to store accounts securely according to go-ethereum encryption protocol.
     * The keystore path is unique to each network id,
     * for example Ropsten network will be: ../data/kin/keystore/3/
     *
     * @param context provide the path to internal data directories.
     * @throws EthereumClientException if could not create directory to save the keystore.
     */
    private void initKeyStore(android.content.Context context) throws EthereumClientException {
        String networkId = String.valueOf(serviceProvider.getNetworkId());
        String keyStorePath = new StringBuilder(context.getFilesDir().getAbsolutePath())
            .append(File.separator)
            .append("kin")
            .append(File.separator)
            .append("keystore")
            .append(File.separator)
            .append(networkId).toString();

        // Make directories if necessary, the keystore will be saved there.
        File keystoreDir = new File(keyStorePath);
        if (!keystoreDir.exists()) {
            if (!keystoreDir.mkdirs()) {
                throw new EthereumClientException("keystore - could not create directory");
            }
        }
        // Create a keyStore instance according to go-ethereum encryption protocol.
        keyStore = Geth.newKeyStore(keystoreDir.getAbsolutePath(), Geth.LightScryptN, Geth.LightScryptP);
    }

    /**
     * @return {@link KeyStore} that will handle all operations related to accounts.
     */
    KeyStore getKeyStore() {
        return keyStore;
    }

    /**
     * Transfer amount of KIN from account to the specified public address.
     *
     * @param from the sender {@link Account}
     * @param passphrase the passphrase used to create the account
     * @param publicAddress the address to send the KIN to
     * @param amount the amount of KIN to send
     * @return {@link TransactionId} of the transaction
     * @throws InsufficientBalanceException if the account has not enough KIN
     * @throws PassphraseException if the transaction could not be signed with the passphrase specified
     * @throws OperationFailedException another error occurred
     */
    TransactionId sendTransaction(Account from, String passphrase, String publicAddress, BigDecimal amount)
        throws InsufficientBalanceException, OperationFailedException, PassphraseException {
        Transaction transaction;
        Address toAddress;
        BigInt amountBigInt;

        // Verify public address is valid.
        if (publicAddress == null || publicAddress.isEmpty()) {
            throw new OperationFailedException("Addressee not valid - public address can't be null or empty");
        }
        // Create the public Address.
        try {
            toAddress = Geth.newAddressFromHex(publicAddress);
        } catch (Exception e) {
            throw new OperationFailedException(e);
        }

        // Make sure the amount is positive and the sender account has enough KIN to send.
        if (amount.signum() != -1) {
            if (hasEnoughBalance(from, amount)) {
                amountBigInt = KinConverter.fromKin(amount);
            } else {
                throw new InsufficientBalanceException();
            }
        } else {
            throw new OperationFailedException("Amount can't be negative");
        }

        try {
            nonce = ethereumClient.getPendingNonceAt(gethContext, from.getAddress());
            gasPrice = ethereumClient.suggestGasPrice(gethContext);
        } catch (Exception e) {
            throw new OperationFailedException(e);
        }

        // Create TransactionOps and send to Kin smart-contract with the required params.
        TransactOpts transactOpts = new TransactOpts();
        transactOpts.setContext(gethContext);
        transactOpts.setGasLimit(KinConsts.DEFAULT_GAS_LIMIT);
        transactOpts.setGasPrice(gasPrice);
        transactOpts.setNonce(nonce);
        transactOpts.setFrom(from.getAddress());
        transactOpts.setSigner(new KinSigner(from, getKeyStore(), passphrase, serviceProvider.getNetworkId()));

        Interface paramToAddress = Geth.newInterface();
        paramToAddress.setAddress(toAddress);

        Interface paramAmount = Geth.newInterface();
        paramAmount.setBigInt(amountBigInt);
        Interfaces params = Geth.newInterfaces(2);
        try {
            params.set(0, paramToAddress);
            params.set(1, paramAmount);
            // Send transfer call to Kin smart-contract.
            transaction = boundContract.transact(transactOpts, "transfer", params);
        } catch (Exception e) {
            throw new OperationFailedException(e);
        }

        return new TransactionIdImpl(transaction.getHash().getHex());
    }

    /**
     * Get balance for the specified account.
     *
     * @param account the {@link Account} to check balance
     * @return the account {@link Balance}
     * @throws OperationFailedException if could not retrieve balance
     */
    Balance getBalance(Account account) throws OperationFailedException {
        Interface balanceResult;
        try {
            Interface paramAddress = Geth.newInterface();
            paramAddress.setAddress(account.getAddress());

            Interfaces params = Geth.newInterfaces(1);
            params.set(0, paramAddress);

            balanceResult = Geth.newInterface();
            balanceResult.setDefaultBigInt();

            Interfaces results = Geth.newInterfaces(1);
            results.set(0, balanceResult);

            CallOpts opts = Geth.newCallOpts();
            opts.setContext(gethContext);

            // Send balanceOf call to Kin smart-contract.
            boundContract.call(opts, results, "balanceOf", params);
        } catch (Exception e) {
            throw new OperationFailedException(e);
        }

        // Check for result, could be null if there was a problem with go-ethereum.
        if (balanceResult.getBigInt() != null) {
            BigDecimal valueInKin = KinConverter.toKin(balanceResult.getBigInt());
            return new BalanceImpl(valueInKin);
        } else {
            throw new OperationFailedException("Could not retrieve balance");
        }
    }

    Balance getPendingBalance(Account account) throws OperationFailedException {
        Balance balance = getBalance(account);
        return pendingBalance.calculate(account, balance);
    }

    ServiceProvider getServiceProvider() {
        return serviceProvider;
    }

    private boolean hasEnoughBalance(Account account, BigDecimal amount) throws OperationFailedException {
        Balance balance = getBalance(account);
        // (> -1) means bigger than or equals to the amount.
        return balance.value().subtract(amount).compareTo(BigDecimal.ZERO) > -1;
    }

    /**
     * @return the contract address to interact with, depends on network.
     */
    private String getContractAddress() {
        String address;
        switch (serviceProvider.getNetworkId()) {
            case ServiceProvider.NETWORK_ID_MAIN:
                address = KinConsts.CONTRACT_ADDRESS_HEX_MAIN_NET;
                break;
            case ServiceProvider.NETWORK_ID_ROPSTEN:
                address = KinConsts.CONTRACT_ADDRESS_HEX_ROPSTEN;
                break;
            default:
                address = KinConsts.CONTRACT_ADDRESS_HEX_ROPSTEN;
                break;
        }
        return address;
    }
}