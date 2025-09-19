package bonampak.libpam4j.client;

import org.jvnet.libpam.PAM;
import org.jvnet.libpam.UnixUser;
import picocli.CommandLine;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

@CommandLine.Command(name = "verifyGroupNames", mixinStandardHelpOptions = true, version = "pamAuthenticate 1.0",
description = "Verifies pam authentication")
public class PamAuthenticationCommand implements Callable<Void> {
    private static final Logger LOGGER = Logger.getLogger(PamAuthenticationCommand.class.getName());

    @CommandLine.Option(names = {"-s", "--serviceName"}, required = true, description = "PAM service name, e.g. login or ssh")
    private String serviceName;

    @CommandLine.Option(names = {"-u", "--userNames"}, required = true, split = ",", description = "user1,user2, ...")
    private List<String> userNames;

    @CommandLine.Option(names = {"-p", "--passwords"}, required = true, split = ",", description = "password1,password2, ...")
    private List<String> passwords;

    @CommandLine.Option(names = {"-e", "--executors"}, description = "number of threads, e.g. 50")
    private int nrOfExecutors;

    @CommandLine.Option(names = {"-c", "--callables"}, description = "number of calls, e.g. 1000")
    int nrOfCallables;

    @Override
    public Void call() {
        LOGGER.info("userNames:" + userNames + " executors:" + nrOfExecutors + " callables:" + nrOfCallables);
        int cores = Runtime.getRuntime().availableProcessors();
        LOGGER.info("available cores:" + cores);

        UserData userData = new UserData(userNames, passwords);
        UnixUserProducer producer = new UnixUserProducer(serviceName, userData, nrOfExecutors, nrOfCallables);
        producer.produceUnixUsers();
        return null;
    }

    private static class UserData {
        final Map<String, String> userNamesToPasswords = new ConcurrentHashMap<>();
        final List<String> userNames;
        private final int nrOfUsers;
        private final Random random = new Random();

        public UserData(List<String> userNames, List<String> passwords) {
            this.userNames = new CopyOnWriteArrayList<>(userNames);
            for (int i = 0; i < userNames.size(); i++) {
                String userName = userNames.get(i);
                String password = passwords.get(i);
                userNamesToPasswords.put(userName, password);
            }
            this.nrOfUsers = userNamesToPasswords.size();
        }

        public String getPasswordByUserName(String userName) {
            return userNamesToPasswords.get(userName);
        }

        public String getRandomUserName() {
            int idx = random.nextInt(nrOfUsers);
            return userNames.get(idx);
        }
    }


    private static class UnixUserProducer {
        private final UserData userData;
        private final int nrOfExecutors;
        private final int nrOfCallables;
        private final String serviceName;

        public UnixUserProducer(String serviceName, UserData userData, int nrOfExecutors, int nrOfCallables) {
            this.nrOfExecutors = nrOfExecutors;
            this.nrOfCallables = nrOfCallables;
            this.userData = userData;
            this.serviceName = serviceName;
        }

        public void produceUnixUsers() {
            ExecutorService producerPool = Executors.newFixedThreadPool(nrOfExecutors);
            final CompletionService<UnixUser> service = new ExecutorCompletionService<>(producerPool);

            try {
                for (int i = 0; i < nrOfCallables; i++) {
                    String userName = userData.getRandomUserName();
                    String password = userData.getPasswordByUserName(userName);

                    service.submit(new AuthenticateUserCallable(serviceName, userName, password));
                }

                for (int i = 0; i < nrOfCallables; i++) {
                    Future<UnixUser> future = service.take();
                    if (future != null) {
                        UnixUser user = future.get();
                        LOGGER.info("authenticated user " + user.getUserName());
                    }
                }

            } catch (ExecutionException | InterruptedException ex) {
                LOGGER.log(Level.SEVERE, "Exception caught", ex);
            } finally {
                producerPool.shutdownNow();
            }
        }

    }


    private static class AuthenticateUserCallable implements Callable<UnixUser> {

        private final String pamServiceName;
        private final String userName;
        private final String password;

        public AuthenticateUserCallable(String pamServiceName, String userName, String password) {
            this.pamServiceName = pamServiceName;
            this.userName = userName;
            this.password = password;
        }

        @Override
        public UnixUser call() throws Exception {
            PAM pam = null;
            try {
                pam = new PAM(pamServiceName);
                return pam.authenticate(userName, password);
            } finally {
                if (pam != null) {
                    pam.dispose();
                }
            }
        }
    }

    public static void main(String... args) {
        int exitCode = new CommandLine(new PamAuthenticationCommand()).execute(args);
        System.exit(exitCode);
    }

}
