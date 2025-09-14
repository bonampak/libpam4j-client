package bonampak.libpam4j.client;

import org.jvnet.libpam.PAMException;
import org.jvnet.libpam.UnixUser;

import java.util.Arrays;
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

public class Main {

    private static final Logger LOGGER = Logger.getLogger(Main.class.getName());
    private static final BlockingQueue<UnixUser> unixUsersQueue = new LinkedBlockingDeque<>();

    public static void main(String[] args) {

        String userName1 = args[0];
        String userName2 = args[1];
        int nrOfExecutors = Integer.parseInt(args[2]);
        int nrOfCallables = Integer.parseInt(args[3]);


        LOGGER.info("username: " + userName1 +
        ", userName2: " + userName2 +
        ", nrOfExecutors: " + nrOfExecutors +
        ", nrOfCallables: " + nrOfCallables);

        int cores = Runtime.getRuntime().availableProcessors();
        LOGGER.info("available cores:" + cores);

        UnixUser user1 = null;
        try {
            user1 = new UnixUser(userName1);
        } catch (PAMException e) {
            LOGGER.log(Level.SEVERE, "Exception caught", e);
            System.exit(-1);
        }
        Set<String> groups1 = user1.getGroups();
        LOGGER.info("user1 groups: " + groups1);

        UnixUser user2 = null;
        try {
            user2 = new UnixUser(userName2);
        } catch (PAMException e) {
            LOGGER.log(Level.SEVERE, "Exception caught", e);
            System.exit(-1);
        }
        Set<String> groups2 = user2.getGroups();
        LOGGER.info("user2 groups: " + groups2);

        UserData userData = new UserData(Arrays.asList(user1, user2));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        final CompletionService<Boolean> service = new ExecutorCompletionService<>(pool);

        service.submit(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                UnixUserConsumer consumer = new UnixUserConsumer(userData, nrOfExecutors, nrOfCallables, unixUsersQueue);
                return consumer.consume();
            }
        });


        service.submit(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                UnixUserProducer producer = new UnixUserProducer(userData, nrOfExecutors, nrOfCallables, unixUsersQueue);
                return producer.offerUsers();
            }
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

        public boolean offerUsers() {
            ExecutorService pool = Executors.newFixedThreadPool(nrOfExecutors);
            final CompletionService<UnixUser> service = new ExecutorCompletionService<>(pool);

            for (int i = 0; i < nrOfCallables; i++) {
                service.submit(new UserCallable(userData.getRandomUserName()));
            }


            try {
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
            } catch (ExecutionException | InterruptedException ex) {
                LOGGER.log(Level.SEVERE, "Exception caught", ex);
                return false;
            } finally {
                pool.shutdownNow();
            }
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
            ExecutorService pool = Executors.newFixedThreadPool(nrOfExecutors);
            final CompletionService<Boolean> service = new ExecutorCompletionService<>(pool);



            int receivedUsers = 0;
            while (receivedUsers < nrOfCallables) {
                UnixUser user = null;
                try {
                    user = userQueue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                receivedUsers++;
                LOGGER.info("consumer got "+user.getUserName()+" with groups "+user.getGroups());
                service.submit(new UserCheckCallable(user, userData));
            }


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
                    LOGGER.log(Level.INFO, "Found " + nrOfErrors + " invalid user responses.");
                }

                return true;
            } catch (ExecutionException | InterruptedException ex) {
                LOGGER.log(Level.SEVERE, "Exception caught", ex);
                return false;
            } finally {
                pool.shutdownNow();
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

    private static class UserCallable implements Callable<UnixUser> {

        private final String userName;

        public UserCallable(String userName) {
            this.userName = userName;
        }

        @Override
        public UnixUser call() throws Exception {
            return new UnixUser(userName);
        }
    }

}
