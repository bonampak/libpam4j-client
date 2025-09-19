package bonampak.libpam4j.client;

import org.jvnet.libpam.PAMException;
import org.jvnet.libpam.UnixUser;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toConcurrentMap;

@Command(name = "verifyGroupNames", mixinStandardHelpOptions = true, version = "verifyGroupNames 1.0",
description = "Verifies the thread safety of user group names")
public class VerifyGroupNamesCommand implements Callable<Integer> {

    private static final Logger LOGGER = Logger.getLogger(VerifyGroupNamesCommand.class.getName());

    @Option(names = {"-u", "--userNames"}, required = true, split = ",", description = "user1,user2, ...")
    private List<String> userNames;

    @Option(names = {"-e", "--executors"}, description = "number of threads, e.g. 50")
    private int nrOfExecutors;

    @Option(names = {"-c", "--callables"}, description = "number of calls, e.g. 1000")
    int nrOfCallables;

    private static final BlockingQueue<UnixUser> unixUsersQueue = new LinkedBlockingDeque<>();


    @Override
    public Integer call() throws Exception {
        LOGGER.info("userNames:" + userNames + " executors:" + nrOfExecutors + " callables:" + nrOfCallables);
        int cores = Runtime.getRuntime().availableProcessors();
        LOGGER.info("available cores:" + cores);

        List<UnixUser> unixUsers = createUnixUsers();

        UserData userData = new UserData(unixUsers);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        final CompletionService<Boolean> service = new ExecutorCompletionService<>(pool);

        service.submit(() -> {
            UnixUserConsumer consumer = new UnixUserConsumer(userData, nrOfExecutors, nrOfCallables, unixUsersQueue);
            return consumer.consume();
        });


        service.submit(() -> {
            UnixUserProducer producer = new UnixUserProducer(userData, nrOfExecutors, nrOfCallables, unixUsersQueue);
            return producer.produceUnixUsers();
        });


        try {
            for (int i = 0; i < 2; i++) {
                Future<Boolean> future = service.take();
                if (future != null) {
                    boolean result = future.get();
                    if (!result) {
                        LOGGER.log(Level.SEVERE, "Problem with producer/consumer");
                    }
                }
            }
        } catch (ExecutionException | InterruptedException ex) {
            LOGGER.log(Level.SEVERE, "Exception caught", ex);
        } finally {
            pool.shutdownNow();
        }
        return 0;
    }

    private List<UnixUser> createUnixUsers() throws PAMException {
        List<UnixUser> unixUsers = new ArrayList<>();
        for (String username : userNames) {
            try {
                unixUsers.add(new UnixUser(username));
            } catch (PAMException e) {
                LOGGER.log(Level.SEVERE, "Exception caught for username:" + username, e);
                throw e;
            }
        }
        return unixUsers;
    }


    private static class UserData {
        private final Map<String, UnixUser> usersByName;
        private final List<String> userNames;
        private final int nrOfUsers;
        private final Random random = new Random();

        public UserData(List<UnixUser> users) {
            this.usersByName = users.stream().collect(toConcurrentMap(UnixUser::getUserName, Function.identity(),
            (first, second) -> first));
            this.userNames = users.stream().map(UnixUser::getUserName).collect(Collectors.toList());
            this.nrOfUsers = usersByName.size();
        }

        public UnixUser getByUserName(String userName) {
            return usersByName.get(userName);
        }

        public boolean isValidUserName(String userName) {
            return usersByName.containsKey(userName);
        }

        public String getRandomUserName() {
            int idx = random.nextInt(nrOfUsers);
            return userNames.get(idx);
        }
    }

    private static class UnixUserProducer {
        private final Queue<UnixUser> userQueue;
        private final UserData userData;
        private final int nrOfExecutors;
        private final int nrOfCallables;

        public UnixUserProducer(UserData userData, int nrOfExecutors, int nrOfCallables, BlockingQueue<UnixUser> userQueue) {
            this.nrOfExecutors = nrOfExecutors;
            this.nrOfCallables = nrOfCallables;
            this.userQueue = userQueue;
            this.userData = userData;
        }

        public boolean produceUnixUsers() {
            ExecutorService producerPool = Executors.newFixedThreadPool(nrOfExecutors);
            final CompletionService<UnixUser> service = new ExecutorCompletionService<>(producerPool);

            try {
                for (int i = 0; i < nrOfCallables; i++) {
                    service.submit(new CreateUserByNameCallable(userData.getRandomUserName()));
                }

                return takeAndOfferUsersToConsumerQueue(service);
            } catch (ExecutionException | InterruptedException ex) {
                LOGGER.log(Level.SEVERE, "Exception caught", ex);
                return false;
            } finally {
                producerPool.shutdownNow();
            }
        }

        private boolean takeAndOfferUsersToConsumerQueue(CompletionService<UnixUser> service) throws InterruptedException, ExecutionException {
            Future<UnixUser> future;

            for (int i = 0; i < nrOfCallables; i++) {
                future = service.take();
                if (future != null) {
                    UnixUser user = future.get();
                    LOGGER.info("offering user " + user.getUserName() + " groups: " + user.getGroups());
                    userQueue.offer(user);
                }
            }

            return true;
        }
    }


    private static class UnixUserConsumer {

        private final BlockingQueue<UnixUser> userQueue;
        private final UserData userData;
        private final int nrOfExecutors;
        private final int nrOfCallables;

        public UnixUserConsumer(UserData userData, int nrOfExecutors, int nrOfCallables, BlockingQueue<UnixUser> userQueue) {
            this.nrOfExecutors = nrOfExecutors;
            this.nrOfCallables = nrOfCallables;
            this.userQueue = userQueue;
            this.userData = userData;
        }

        public boolean consume() {
            LOGGER.info("starting consumer");
            ExecutorService consumerPool = Executors.newFixedThreadPool(nrOfExecutors);
            final CompletionService<Boolean> service = new ExecutorCompletionService<>(consumerPool);

            readQueueAndSubmitToConsumerPool(service);
            return collectResultsFromConsumerPool(service, consumerPool);

        }

        private boolean collectResultsFromConsumerPool(CompletionService<Boolean> service, ExecutorService consumerPool) {
            try {
                Future<Boolean> future;
                int nrOfErrors = 0;

                for (int i = 0; i < nrOfCallables; i++) {
                    future = service.take();
                    if (future != null) {
                        Boolean isValid = future.get();
                        if (!isValid) {
                            nrOfErrors++;
                        }
                    }
                }
                if (nrOfErrors > 0) {
                    LOGGER.log(Level.INFO, "Found " + nrOfErrors + " invalid users.");
                }
                return true;
            } catch (ExecutionException | InterruptedException ex) {
                LOGGER.log(Level.SEVERE, "Exception caught", ex);
                return false;
            } finally {
                consumerPool.shutdownNow();
            }
        }

        private void readQueueAndSubmitToConsumerPool(CompletionService<Boolean> service) {
            int receivedUsers = 0;
            while (receivedUsers < nrOfCallables) {
                try {
                    UnixUser user = userQueue.take();
                    receivedUsers++;
                    LOGGER.info("consumer got " + user.getUserName() + " with groups " + user.getGroups());
                    service.submit(new UserCheckCallable(user, userData));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

    }

    private static class UserCheckCallable implements Callable<Boolean> {

        private final UnixUser unixUser;
        private final UserData userData;

        public UserCheckCallable(UnixUser unixUser, UserData userData) {
            this.unixUser = unixUser;
            this.userData = userData;
        }

        @Override
        public Boolean call() {
            boolean isValid = true;
            String actualUserName = unixUser.getUserName();
            if (!userData.isValidUserName(actualUserName)) {
                LOGGER.log(Level.SEVERE, "invalid user name: " + actualUserName);
                isValid = false;
            }
            Set<String> actualGroups = unixUser.getGroups();
            UnixUser expectedUnixUser = userData.getByUserName(actualUserName);
            Set<String> expectedGroups = expectedUnixUser.getGroups();
            if (actualGroups.size() != expectedGroups.size() || !actualGroups.containsAll(expectedGroups)) {
                LOGGER.log(Level.SEVERE, "invalid user groups: " + actualGroups + ", expected:" + expectedGroups);
                isValid = false;
            }
            return isValid;

        }
    }

    private static class CreateUserByNameCallable implements Callable<UnixUser> {

        private final String userName;

        public CreateUserByNameCallable(String userName) {
            this.userName = userName;
        }

        @Override
        public UnixUser call() throws Exception {
            return new UnixUser(userName);
        }
    }

    public static void main(String... args) {
        int exitCode = new CommandLine(new VerifyGroupNamesCommand()).execute(args);
        System.exit(exitCode);
    }
}
