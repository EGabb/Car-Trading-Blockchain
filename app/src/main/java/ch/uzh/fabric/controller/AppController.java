package ch.uzh.fabric.controller;

import ch.uzh.fabric.config.*;
import ch.uzh.fabric.model.*;
import ch.uzh.fabric.model.User;
import ch.uzh.fabric.service.CarService;
import ch.uzh.fabric.service.UserService;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.hyperledger.fabric.sdk.*;
import org.hyperledger.fabric.sdk.exception.*;
import org.hyperledger.fabric.sdk.security.CryptoSuite;
import org.hyperledger.fabric_ca.sdk.HFCAClient;
import org.hyperledger.fabric_ca.sdk.RegistrationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static ch.uzh.fabric.config.SecurityConfig.*;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;

@Controller
public class AppController {
    private static final Logger LOGGER = LoggerFactory.getLogger(AppController.class);
    public static final TestConfig TESTCONFIG = TestConfig.getConfig();
    private static final String TEST_ADMIN_NAME = "admin";
    private static final String TESTUSER_1_NAME = "user1";
    private static final String FOO_CHAIN_NAME = "foo";
    private static final String PROJECT_ROOT = "/var/egabb/";
    public static final String CHAIN_CODE_NAME = "car_cc_go";
    public static final String CHAIN_CODE_PATH = "github.com/car_cc";
    public static final String CHAIN_CODE_VERSION = "1";

    private static final String TEST_VIN = "WVWZZZ6RZHY260780";
    private static final String TEST_VIN2 = "XYZDZZ6RZHY820780";
    private static final String TEST_VIN3 = "WVWQAW6RZHY140783";
    private static final String TEST_VIN4 = "AVCQA8WJZHY140783";

    public static final SimpleDateFormat timeFormat = new SimpleDateFormat("dd.MM.yyyy, HH:mm");

    private Collection<SampleOrg> testSampleOrgs;
    private SampleStore sampleStore;
    private HFClient client;
    private Chain chain;

    @Autowired
    private UserService userService;
    @Autowired
    private CarService carService;


	/*
     *	URL MAPPINGS
	 *
	 */

    @RequestMapping("/")
    public String root(Authentication authentication) {
        try {
            authentication.isAuthenticated();
            return "redirect:/index";
        } catch (Exception e) {
            return "redirect:/login";
        }
    }

    @RequestMapping(value = "/account", method = RequestMethod.GET)
    public String account(Authentication auth, Model model) {
        String username = auth.getName();
        String role = userService.getRole(auth);

        ProfileProperties.User user = userService.findOrCreateUser(username, role);

        model.addAttribute("orgName", user.getOrganization());
        model.addAttribute("role", role.toUpperCase());
        return "account";
    }

    @RequestMapping(value = "/account", method = RequestMethod.POST)
    public String accountChange(Authentication auth, Model model, @RequestParam("orgName") String orgName) {
        String username = auth.getName();
        String role = userService.getRole(auth);

        ProfileProperties.User user = userService.findOrCreateUser(username, role);
        user.setOrganization(orgName);

        model.addAttribute("orgName", user.getOrganization());
        model.addAttribute("role", role.toUpperCase());
        return "account";
    }

    @RequestMapping("/index")
    public String index(Authentication auth,
                        Model model,
                        RedirectAttributes redirAttr,
                        @RequestParam(required = false) String error,
                        @RequestParam(required = false) String success) {
        String username = auth.getName();
        String role = userService.getRole(auth);

        if (role.equals("dot")) {
            return "redirect:/dot/registration";
        } else if (role.equals("insurer")) {
            return "redirect:/insurance/index";
        }

        Collection<Car> cars = new ArrayList<>();

        try {
            cars = carService.getCars(client, chain, username, role);
        } catch (Exception e) {
            error = e.getMessage();
        }

        model.addAttribute("cars", cars);
        model.addAttribute("role", role.toUpperCase());
        model.addAttribute("success", success);
        model.addAttribute("error", error);
        return "index";
    }

    @RequestMapping(value = "/import", method = RequestMethod.GET)
    public String importCar(Model model,
                            Authentication auth,
                            @RequestParam(required = false) String error,
                            @RequestParam(required = false) String success,
                            @ModelAttribute Car car,
                            @ModelAttribute ProposalData proposalData) {
        String role = userService.getRole(auth);
        model.addAttribute("role", role.toUpperCase());
        model.addAttribute("success", success);
        model.addAttribute("error", error);
        return "import";
    }

    @RequestMapping(value = "/import", method = RequestMethod.POST)
    public String importCar(Model model,
                            RedirectAttributes redirAttr,
                            Authentication auth,
                            @ModelAttribute Car car,
                            @ModelAttribute ProposalData proposalData) {
        String username = (model != null) ? auth.getName() : SecurityConfig.BOOTSTRAP_GARAGE_USER;
        String role = (model != null) ? userService.getRole(auth) : SecurityConfig.BOOTSTRAP_GARAGE_ROLE;

        proposalData.setCar(car.getVin());
        try {
            carService.importCar(client, chain, username, role, car, proposalData);
        } catch (Exception e) {
            redirAttr.addAttribute("error", e.getMessage());
            return "redirect:/import";
        }

        if (model != null && redirAttr != null) {
            model.addAttribute("role", role.toUpperCase());
            redirAttr.addAttribute("success", "Successfully imported car with VIN '" + car.getVin() + "'");
        }

        return "redirect:/index";
    }

    @RequestMapping("/login")
    public String login() {
        return "login";
    }

    @RequestMapping("/login-error")
    public String loginError(Model model) {
        model.addAttribute("loginError", true);
        return "login";
    }

    @RequestMapping(value = "/dot/registration", method = RequestMethod.GET)
    public String registration(Model model,
                               RedirectAttributes redirAttr,
                               Authentication auth,
                               @RequestParam(required = false) String success,
                               @RequestParam(required = false) String error) {
        String username = (model != null) ? auth.getName() : SecurityConfig.BOOTSTRAP_DOT_USER;
        String role = (model != null) ? userService.getRole(auth) : SecurityConfig.BOOTSTRAP_DOT_ROLE;

        Collection<ProposalData> registrationProposals;
        Collection<ProposalAndCar> proposalsAndCars = new ArrayList<>();

        try {
            registrationProposals = carService.getRegistrationProposals(client, chain, username, role);
            for (ProposalData proposal : registrationProposals) {
                Car car = carService.getCar(client, chain, proposal.getUsername(), role, proposal.getCar());
                proposalsAndCars.add(new ProposalAndCar(proposal, car));
            }
        } catch (Exception e) {
            error = e.getMessage();
        }

        model.addAttribute("success", success);
        model.addAttribute("error", error);
        model.addAttribute("proposalAndCarData", proposalsAndCars);
        model.addAttribute("role", role.toUpperCase());

        return "dot/registration";
    }

    @RequestMapping(value = "/dot/registration", method = RequestMethod.POST)
    public String registrationAccept(RedirectAttributes redirAttr,
                                     Authentication auth,
                                     @RequestParam String vin,
                                     @RequestParam String owner) {
        String role = (redirAttr != null) ? userService.getRole(auth) : SecurityConfig.BOOTSTRAP_DOT_ROLE;

        try {
            carService.register(client, chain, owner, role, vin);
        } catch (Exception e) {
            redirAttr.addAttribute("error", e.getMessage());
            return "redirect:/dot/registration";
        }

        if (redirAttr != null) {
            redirAttr.addAttribute("success", "Registration proposal accepted. Car with Vin: '" + vin + "' is successfully registered.");
        }

        return "redirect:/dot/registration";
    }

    @RequestMapping(value = "/dot/confirmation", method = RequestMethod.GET)
    public String confirm(Model model,
                          RedirectAttributes redirAttr,
                          Authentication auth,
                          @RequestParam(required = false) String success,
                          @RequestParam(required = false) String error) {
        String username = auth.getName();
        String role = userService.getRole(auth);

        Collection<Car> carsToConfirm = new ArrayList<>();

        try {
            carsToConfirm = carService.getCarsToConfirm(client, chain, username, role);
        } catch (Exception e) {
            error = e.getMessage();
        }

        model.addAttribute("success", success);
        model.addAttribute("error", error);
        model.addAttribute("cars", carsToConfirm);
        model.addAttribute("role", role.toUpperCase());

        return "dot/confirmation";
    }

    @RequestMapping(value = "/dot/confirmation", method = RequestMethod.POST)
    public String confirmationAccept(RedirectAttributes redirAttr,
                                     Authentication auth,
                                     @RequestParam String vin,
                                     @RequestParam String numberplate) {
        String username = (redirAttr != null) ? auth.getName() : SecurityConfig.BOOTSTRAP_DOT_USER;
        String role = (redirAttr != null) ? userService.getRole(auth) : SecurityConfig.BOOTSTRAP_DOT_ROLE;

        try {
            carService.confirm(client, chain, username, role, vin, numberplate);
        } catch (Exception e) {
            redirAttr.addAttribute("error", e.getMessage());
            return "redirect:/dot/confirmation";
        }

        redirAttr.addAttribute("success", "Confirmation for car '" + vin + "' with numberplate '" + numberplate + "' successful.");
        return "redirect:/dot/confirmation";
    }

    @RequestMapping("/revocationProposal")
    public String revocationProposal(RedirectAttributes redirAttr, Authentication auth, @RequestParam String vin) {
        String username = auth.getName();
        String role = userService.getRole(auth);

        try {
            carService.revocationProposal(client, chain, username, role, vin);
        } catch (Exception e) {
            redirAttr.addAttribute("error", e.getMessage());
            return "redirect:/index";
        }

        redirAttr.addAttribute("success", "Request for revocation registered, DOT is notified.");
        return "redirect:/index";
    }

    @RequestMapping(value = "/dot/revocation", method = RequestMethod.GET)
    public String revocation(Model model,
                             RedirectAttributes redirAttr,
                             Authentication auth,
                             @RequestParam(required = false) String error,
                             @RequestParam(required = false) String success) {
        String username = auth.getName();
        String role = userService.getRole(auth);

        Collection<Car> carList = new ArrayList<>();

        try {
            Map<String, String> revocationProposals = carService.getRevocationProposals(client, chain, username, role);
            for (Map.Entry<String, String> e : revocationProposals.entrySet()) {
                Car car = carService.getCar(client, chain, e.getValue(), role, e.getKey());
                carList.add(car);
            }
        } catch (Exception e) {
            error = e.getMessage();
        }

        model.addAttribute("cars", carList);
        model.addAttribute("error", error);
        model.addAttribute("success", success);
        model.addAttribute("role", role.toUpperCase());
        return "dot/revocation";
    }

    @RequestMapping(value = "/dot/revocation", method = RequestMethod.POST)
    public String revocationAccept(RedirectAttributes redirAttr, Authentication auth, @RequestParam String vin, @RequestParam String owner) {
        String role = userService.getRole(auth);

        try {
            carService.revoke(client, chain, owner, role, vin);
        } catch (Exception e) {
            redirAttr.addAttribute("error", e.getMessage());
            return "redirect:/dot/revocation";
        }

        redirAttr.addAttribute("success", "Successfully revoked car with VIN '" + vin + "' of user '" + owner + "'");
        return "redirect:/dot/revocation";
    }

    @RequestMapping("/dot/all-cars")
    public String allCars(RedirectAttributes redirAttr, Authentication auth, Model model) {
        String username = auth.getName();
        String role = userService.getRole(auth);
        Collection<Car> cars = new ArrayList<>();

        try {
            cars = carService.getAllCars(client, chain, username, role);
        } catch (Exception e) {
            model.addAttribute("error", e.getMessage());
        }

        model.addAttribute("cars", cars);
        model.addAttribute("role", role.toUpperCase());
        return "dot/all-cars";
    }

    @RequestMapping(value = "/insurance/index", method = RequestMethod.GET)
    public String insurance(Model model,
                            Authentication auth,
                            @RequestParam(required = false) String success,
                            @RequestParam(required = false) String error) {
        String username = auth.getName();
        String role = userService.getRole(auth);

        ProfileProperties.User user = userService.findOrCreateUser(username, role);
        String companyName = user.getOrganization();
        Insurer insurer;

        try {
            insurer = carService.getInsurer(client, chain, username, role, companyName);
            for (InsureProposal proposal : insurer.getProposals()) {
                Car car = carService.getCar(client, chain, proposal.getUser(), "user", proposal.getCar());
                proposal.setRegistered(car.isRegistered());
            }
        } catch (Exception e) {
            insurer = new Insurer(companyName, null);
        }

        model.addAttribute("success", success);
        model.addAttribute("error", error);
        model.addAttribute("role", role.toUpperCase());
        model.addAttribute("insurer", insurer);
        return "insurance/index";
    }

    @RequestMapping(value = "/insurance/index", method = RequestMethod.POST)
    public String insuranceAccept(RedirectAttributes redirAttr,
                                  Authentication auth,
                                  @RequestParam String vin,
                                  @RequestParam String userToInsure) {
        String username = (redirAttr != null) ? auth.getName() : SecurityConfig.BOOTSTRAP_INSURANCE_USER;
        String role = (redirAttr != null) ? userService.getRole(auth) : SecurityConfig.BOOTSTRAP_INSURANCE_USER;

        ProfileProperties.User user = userService.findOrCreateUser(username, role);
        String company = user.getOrganization();

        try {
            carService.acceptInsurance(client, chain, username, role, userToInsure, vin, company);
        } catch (Exception e) {
            if (redirAttr != null) {
                redirAttr.addAttribute("error", e.getMessage());
            }
        }

        if (redirAttr != null) {
            redirAttr.addAttribute("success", "Insurance proposal accepted. '" + company + "' now insures car '" + vin + "' of user '" + userToInsure + "'.");
        }

        return "redirect:/insurance/index";
    }

    @RequestMapping(value = "/insure", method = RequestMethod.GET)
    public String insure(Model model,
                         RedirectAttributes redirAttr,
                         Authentication auth,
                         @RequestParam(required = false) String success,
                         @RequestParam(required = false) String error,
                         @RequestParam(required = false) String activeVin) {
        String username = auth.getName();
        String role = userService.getRole(auth);
        Collection<Car> cars = new ArrayList<>();

        try {
            cars = carService.getCars(client, chain, username, role);
        } catch (Exception e) {
            error = e.getMessage();
        }

        model.addAttribute("activeVin", activeVin);
        model.addAttribute("error", error);
        model.addAttribute("success", success);
        model.addAttribute("cars", cars);
        model.addAttribute("role", role.toUpperCase());
        return "insure";
    }

    @RequestMapping(value = "/insure", method = RequestMethod.POST)
    public String insure(RedirectAttributes redirAttr, Authentication auth, @RequestParam String vin, @RequestParam String company) {
        String username = (redirAttr != null) ? auth.getName() : SecurityConfig.BOOTSTRAP_GARAGE_USER;
        String role = (redirAttr != null) ? userService.getRole(auth) : SecurityConfig.BOOTSTRAP_GARAGE_ROLE;

        try {
            carService.insureProposal(client, chain, username, role, vin, company);
        } catch (Exception e) {
            redirAttr.addAttribute("error", e.getMessage());
            return "redirect:/insure";
        }

        if (redirAttr != null) {
            redirAttr.addAttribute("success", "Insurance proposal saved. '" + company + "' will get back to you for confirmation.");
        }

        return "redirect:/insure";
    }

    @RequestMapping(value = "/sell", method = RequestMethod.GET)
    public String sell(Model model,
                       RedirectAttributes redirAttr,
                       Authentication auth,
                       @RequestParam(required = false) String success,
                       @RequestParam(required = false) String error,
                       @RequestParam(required = false) String activeVin) {
        String username = auth.getName();
        String role = userService.getRole(auth);
        Collection<Car> cars = new ArrayList<>();

        try {
            cars = carService.getCars(client, chain, username, role);
        } catch (Exception e) {
            error = e.getMessage();
        }

        model.addAttribute("activeVin", activeVin);
        model.addAttribute("success", success);
        model.addAttribute("error", error);
        model.addAttribute("cars", cars);
        model.addAttribute("role", role.toUpperCase());
        return "sell";
    }

    @RequestMapping(value = "/sell", method = RequestMethod.POST)
    public String sellOfferCreate(RedirectAttributes redirAttr,
                                  Authentication auth,
                                  @RequestParam String vin,
                                  @RequestParam(required = false) String buyer,
                                  @RequestParam(required = false) String price) {
        String username = auth.getName();
        String role = userService.getRole(auth);

        try {
            carService.createSellingOffer(client, chain, username, role, price, vin, buyer);
        } catch (Exception e) {
            redirAttr.addAttribute("error", e.getMessage());
            return "redirect:/sell";
        }

        redirAttr.addAttribute("success", "Successfully sent selling offer for car '" + vin + "' to user '" + buyer + "'");
        return "redirect:/sell";
    }

    @RequestMapping(value = "/offers", method = RequestMethod.GET)
    public String sellOfferShow(Model model,
                                Authentication auth,
                                @RequestParam(required = false) String success,
                                @RequestParam(required = false) String error) {
        String username = auth.getName();
        String role = userService.getRole(auth);

        Collection<Offer> offers = new ArrayList<>();

        try {
            offers = carService.getSalesOffers(client, chain, username, role);
        } catch (Exception e) {
            error = e.getMessage();
        }

        model.addAttribute("success", success);
        model.addAttribute("error", error);
        model.addAttribute("offers", offers);
        model.addAttribute("role", role.toUpperCase());
        return "offers";
    }


    @RequestMapping(value = "/offers", method = RequestMethod.POST)
    public String sellOfferAccept(RedirectAttributes redirAttr,
                                  Authentication auth,
                                  @RequestParam String vin,
                                  @RequestParam String seller,
                                  @RequestParam String price) {
        String username = auth.getName();
        String role = userService.getRole(auth);

        try {
            carService.sell(client, chain, seller, role, vin, username);
        } catch (Exception e) {
            redirAttr.addAttribute("error", e.getMessage());
            return "redirect:/offers";
        }

        redirAttr.addAttribute("success", "Successfully bought car '" + vin + "' from user '" + seller + "' at price '" + price + "'.");
        return "redirect:/index";
    }

    @RequestMapping(value = "/history", method = RequestMethod.GET)
    public String history(Model model,
                          RedirectAttributes redirAttr,
                          Authentication auth,
                          @RequestParam String vin,
                          @RequestParam(required = false) String error) {
        String username = auth.getName();
        String role = userService.getRole(auth);

        Map<Integer, Car> history = new HashMap<>();
        try {
            history = carService.getCarHistory(client, chain, username, role, vin);
        } catch (Exception e) {
            error = e.getMessage();
        }

        model.addAttribute("error", error);
        model.addAttribute("vin", vin);
        model.addAttribute("history", history);
        model.addAttribute("timeFmt", timeFormat);
        model.addAttribute("role", role.toUpperCase());

        return "history";
    }

    public void createUser(String name) throws Exception {
        carService.createUser(client, chain, BOOTSTRAP_GARAGE_USER, BOOTSTRAP_GARAGE_ROLE, name);
    }



	/*
     *	INITIALIZE FUNCTIONS
	 *
	 */

    @PostConstruct
    public void AppController() throws Exception {
        System.out.println("╔═╗┌─┐┌┐ ┬─┐┬┌─┐  ╔╗ ┌─┐┌─┐┌┬┐┌─┐┌┬┐┬─┐┌─┐┌─┐\n" +
                "╠╣ ├─┤├┴┐├┬┘││    ╠╩╗│ ││ │ │ └─┐ │ ├┬┘├─┤├─┘\n" +
                "╚  ┴ ┴└─┘┴└─┴└─┘  ╚═╝└─┘└─┘ ┴ └─┘ ┴ ┴└─┴ ┴┴  ");

        initSampleStore();
        setupclient();
        getconfig();

        enrolladmin();
        enrollusers();
        enrollorgadmin();
        constructchain();
        installchaincode();
        instantiatechaincode();

        createUser(BOOTSTRAP_GARAGE_USER);
        createUser(BOOTSTRAP_PRIVATE_USER);

        bootstrapCars();
        System.out.println("Hyperledger network is ready to use");

        AppController.timeFormat.setTimeZone(TimeZone.getTimeZone("Europe/Zurich"));
    }

    private void bootstrapCars() {
        // confirmed car, ready to be revoked by the garage user
        importCar(null, null, null, new Car(
                        new Certificate(
                                null,
                                null,
                                null,
                                null,
                                "white",
                                "C350",
                                "Mercedes"), 0, TEST_VIN),
                new ProposalData(
                        "4+1",
                        4,
                        2,
                        200)
        );

        insure(null, null, TEST_VIN, "AXA");
        registrationAccept(null, null, TEST_VIN, SecurityConfig.BOOTSTRAP_GARAGE_USER);
        insuranceAccept(null, null, TEST_VIN, SecurityConfig.BOOTSTRAP_GARAGE_USER);
        //confirmCar(null, null, TEST_VIN, "ZH 1234");

//        // create an unregistered car
//        // with insurance proposal
//        importCar(null, null,null, new Car(
//                        new Certificate(
//                                null,
//                                null,
//                                null,
//                                null,
//                                "blue",
//                                "A8",
//                                "Audi"), 0, TEST_VIN2),
//                new ProposalData(
//                        "5",
//                        8,
//                        2,
//                        200)
//        );
//        insure(null, null, TEST_VIN2, "AXA");
//
//        // create a registered car
//        // without insurance
//        importCar(null, null,null, new Car(
//                        new Certificate(
//                                null,
//                                null,
//                                null,
//                                null,
//                                "red",
//                                "TDI",
//                                "VW"), 0, TEST_VIN3),
//                new ProposalData(
//                        "5",
//                        4,
//                        2,
//                        150)
//        );
//        acceptRegistration(null, null, TEST_VIN2, SecurityConfig.BOOTSTRAP_GARAGE_USER);
//
//        // create a registered and insured car
//        // ready to be confirmed
//        importCar(null, null,null, new Car(
//                        new Certificate(
//                                null,
//                                null,
//                                null,
//                                null,
//                                "grey",
//                                "AMG C 63 S",
//                                "Mercedes"), 0, TEST_VIN4),
//                new ProposalData(
//                        "5",
//                        8,
//                        2,
//                        250)
//        );
//        acceptRegistration(null, null, TEST_VIN4, SecurityConfig.BOOTSTRAP_GARAGE_USER);
//        insure(null, null, TEST_VIN4, "AXA");
//        acceptInsurance(null, null, TEST_VIN4, SecurityConfig.BOOTSTRAP_GARAGE_USER);
    }

    private void initSampleStore() {
        File sampleStoreFile = new File(System.getProperty("java.io.tmpdir") + "/HFCSampletest.properties");
        sampleStore = new SampleStore(sampleStoreFile);
        LOGGER.info(sampleStore.toString());
    }

    private ErrorInfo setupclient() {
        client = HFClient.createNewInstance();
        try {
            client.setCryptoSuite(CryptoSuite.Factory.getCryptoSuite());
        } catch (CryptoException ex) {
            LOGGER.error("CryptoException setting crypto suite", ex);
            ErrorInfo result = new ErrorInfo(500, "", "CryptoException setting crypto suite");
            return result;
        } catch (InvalidArgumentException ex) {
            LOGGER.error("InvalidArgumentException setting crypto suite", ex);
            ErrorInfo result = new ErrorInfo(500, "", "InvalidArgumentException setting crypto suite");
            return result;
        }
        ErrorInfo result = new ErrorInfo(0, "", "OK");
        return result;
    }

    private ErrorInfo getconfig() {
        testSampleOrgs = TESTCONFIG.getIntegrationTestsSampleOrgs();

        for (SampleOrg sampleOrg : testSampleOrgs) {
            try {
                sampleOrg.setCAClient(HFCAClient.createNewInstance(sampleOrg.getCALocation(), sampleOrg.getCAProperties()));
            } catch (MalformedURLException ex) {
                LOGGER.error("MalformedURLException setting client config", ex);
                ErrorInfo result = new ErrorInfo(500, "", "MalformedURLException setting client config");
                return result;
            }
        }

        ErrorInfo result = new ErrorInfo(0, "", "OK");
        return result;
    }

    private List<EnrollAdminResponse> enrolladmin() throws Exception {

        List<EnrollAdminResponse> result = new ArrayList<>();

        for (SampleOrg sampleOrg : testSampleOrgs) {
            HFCAClient ca = sampleOrg.getCAClient();
            final String orgName = sampleOrg.getName();
            final String mspid = sampleOrg.getMSPID();
            ca.setCryptoSuite(CryptoSuite.Factory.getCryptoSuite());
            SampleUser admin = sampleStore.getMember(TEST_ADMIN_NAME, orgName);
            if (!admin.isEnrolled()) {
                admin.setEnrollment(ca.enroll(admin.getName(), "adminpw"));
                admin.setMPSID(mspid);
            }

            sampleOrg.setAdmin(admin);
            EnrollAdminResponse resp = new EnrollAdminResponse(admin.getName(), orgName, admin.getMSPID(), admin.isEnrolled(), admin.isRegistered());
            result.add(resp);
            sampleStore.putMember(TEST_ADMIN_NAME, orgName, admin);
        }

        LOGGER.info(sampleStore.toString());
        return result;
    }

    private List<EnrollAdminResponse> enrollusers() throws Exception {
        List<EnrollAdminResponse> result = new ArrayList<>();

        for (SampleOrg sampleOrg : testSampleOrgs) {

            HFCAClient ca = sampleOrg.getCAClient();
            final String orgName = sampleOrg.getName();
            final String mspid = sampleOrg.getMSPID();
            SampleUser admin = sampleStore.getMember(TEST_ADMIN_NAME, orgName);

            SampleUser user = sampleStore.getMember(TESTUSER_1_NAME, sampleOrg.getName());
            if (!user.isRegistered()) {
                RegistrationRequest rr = new RegistrationRequest(user.getName(), "org1.department1");
                user.setEnrollmentSecret(ca.register(rr, admin));
            }
            if (!user.isEnrolled()) {
                user.setEnrollment(ca.enroll(user.getName(), user.getEnrollmentSecret()));
                user.setMPSID(mspid);
            }
            sampleOrg.addUser(user);
            EnrollAdminResponse resp = new EnrollAdminResponse(user.getName(), orgName, user.getMSPID(), user.isEnrolled(), user.isRegistered());
            result.add(resp);
            sampleStore.putMember(TESTUSER_1_NAME, orgName, user);
        }

        LOGGER.info(sampleStore.toString());
        LOGGER.info(result.toString());
        return result;
    }

    private List<EnrollAdminResponse> enrollorgadmin() throws Exception {
        List<EnrollAdminResponse> result = new ArrayList<>();

        for (SampleOrg sampleOrg : testSampleOrgs) {
            final String orgName = sampleOrg.getName();
            final String sampleOrgName = sampleOrg.getName();
            final String sampleOrgDomainName = sampleOrg.getDomainName();

            SampleUser peerOrgAdmin = sampleStore.getMember(sampleOrgName + "Admin", sampleOrgName, sampleOrg.getMSPID(),
                    findFile_sk(Paths.get(TESTCONFIG.getTestChannlePath(), "crypto-config/peerOrganizations/",
                            sampleOrgDomainName, format("/users/Admin@%s/msp/keystore", sampleOrgDomainName)).toFile()),
                    Paths.get(TESTCONFIG.getTestChannlePath(), "crypto-config/peerOrganizations/", sampleOrgDomainName,
                            format("/users/Admin@%s/msp/signcerts/Admin@%s-cert.pem", sampleOrgDomainName, sampleOrgDomainName)).toFile());

            //A special user that can crate channels, join peers and install chain code
            sampleOrg.setPeerAdmin(peerOrgAdmin);

            EnrollAdminResponse resp = new EnrollAdminResponse(peerOrgAdmin.getName(), orgName, peerOrgAdmin.getMSPID(), peerOrgAdmin.isEnrolled(), peerOrgAdmin.isRegistered());
            result.add(resp);
        }

        return result;
    }

    private File findFile_sk(File directory) {
        File[] matches = directory.listFiles((dir, name) -> name.endsWith("_sk"));

        if (null == matches) {
            throw new RuntimeException(format("Matches returned null does %s directory exist?", directory.getAbsoluteFile().getName()));
        }

        if (matches.length != 1) {
            throw new RuntimeException(format("Expected in %s only 1 sk file but found %d", directory.getAbsoluteFile().getName(), matches.length));
        }

        return matches[0];
    }

    private ErrorInfo constructchain() throws InvalidArgumentException, IOException, TransactionException, ProposalException {
        SampleOrg sampleOrg = TESTCONFIG.getIntegrationTestsSampleOrg("peerOrg1");

        out("Constructing chain %s", FOO_CHAIN_NAME);

        Collection<Orderer> orderers = new LinkedList<>();

        for (String orderName : sampleOrg.getOrdererNames()) {
            orderers.add(client.newOrderer(orderName, sampleOrg.getOrdererLocation(orderName),
                    TESTCONFIG.getOrdererProperties(orderName)));
        }

        //Just pick the first orderer in the list to create the chain.
        Orderer anOrderer = orderers.iterator().next();
        orderers.remove(anOrderer);

        ChainConfiguration chainConfiguration = new ChainConfiguration(new File(PROJECT_ROOT + "/fixtures/e2e-2Orgs/channel/" + FOO_CHAIN_NAME + ".tx"));
        //Only peer Admin org
        client.setUserContext(sampleOrg.getPeerAdmin());

        //Create chain that has only one signer that is this orgs peer admin. If chain creation policy needed more signature they would need to be added too.
        Chain newChain = client.newChain(FOO_CHAIN_NAME, anOrderer, chainConfiguration, client.getChainConfigurationSignature(chainConfiguration, sampleOrg.getPeerAdmin()));

        out("Created chain %s", FOO_CHAIN_NAME);

        out("Adding peers to chain");

        for (String peerName : sampleOrg.getPeerNames()) {
            String peerLocation = sampleOrg.getPeerLocation(peerName);

            //test properties for peer.. if any.
            Properties peerProperties = TESTCONFIG.getPeerProperties(peerName);
            if (peerProperties == null) {
                peerProperties = new Properties();
            }

            //Example of setting specific options on grpc's ManagedChannelBuilder
            peerProperties.put("grpc.ManagedChannelBuilderOption.maxInboundMessageSize", 9000000);

            Peer peer = client.newPeer(peerName, peerLocation, peerProperties);
            newChain.joinPeer(peer);
            out("Peer %s joined chain %s", peerName, FOO_CHAIN_NAME);
            sampleOrg.addPeer(peer);
        }

        out("Adding remaining orderers to chain");

        //add remaining orderers if any.
        for (Orderer orderer : orderers) {
            newChain.addOrderer(orderer);
        }

        out("Adding event hubs to chain");

        for (String eventHubName : sampleOrg.getEventHubNames()) {
            EventHub eventHub = client.newEventHub(eventHubName, sampleOrg.getEventHubLocation(eventHubName),
                    TESTCONFIG.getEventHubProperties(eventHubName));
            newChain.addEventHub(eventHub);
        }

        out("starting chain intialisation");

        newChain.initialize();

        out("Finished initialization chain %s", FOO_CHAIN_NAME);

        chain = newChain;

        ErrorInfo result = new ErrorInfo(0, "", "OK");
        return result;
    }

    private ErrorInfo installchaincode() throws InvalidArgumentException, ProposalException {
        final String chainName = chain.getName();
        out("installing chaincode on chain %s", chainName);
        chain.setTransactionWaitTime(TESTCONFIG.getTransactionWaitTime());
        chain.setDeployWaitTime(TESTCONFIG.getDeployWaitTime());

        final ChainCodeID chainCodeID;
        Collection<ProposalResponse> responses;
        Collection<ProposalResponse> successful = new LinkedList<>();
        Collection<ProposalResponse> failed = new LinkedList<>();

        chainCodeID = ChainCodeID.newBuilder().setName(CHAIN_CODE_NAME)
                .setVersion(CHAIN_CODE_VERSION)
                .setPath(CHAIN_CODE_PATH).build();

        SampleOrg sampleOrg = TESTCONFIG.getIntegrationTestsSampleOrg("peerOrg1");
        client.setUserContext(sampleOrg.getPeerAdmin());

        out("Creating install proposal");

        InstallProposalRequest installProposalRequest = client.newInstallProposalRequest();
        installProposalRequest.setChaincodeID(chainCodeID);

        // install from directory (install from stream also available)
        // For GO language and serving just a single user, chaincodeSource is mostly likely the users GOPATH
        installProposalRequest.setChaincodeSourceLocation(new File(PROJECT_ROOT + "/chaincode"));

        installProposalRequest.setChaincodeVersion(CHAIN_CODE_VERSION);

        out("Sending install proposal");

        // only a client from the same org as the peer can issue an install request
        int numInstallProposal = 0;
        //    Set<String> orgs = orgPeers.keySet();
        //   for (SampleOrg org : testSampleOrgs) {

        Set<Peer> peersFromOrg = sampleOrg.getPeers();
        numInstallProposal = numInstallProposal + peersFromOrg.size();
        responses = client.sendInstallProposal(installProposalRequest, peersFromOrg);

        for (ProposalResponse response : responses) {
            if (response.getStatus() == ProposalResponse.Status.SUCCESS) {
                out("Successful install proposal response Txid: %s from peer %s", response.getTransactionID(), response.getPeer().getName());
                successful.add(response);
            } else {
                failed.add(response);
            }
        }
        //   }
        out("Received %d install proposal responses. Successful+verified: %d . Failed: %d", numInstallProposal, successful.size(), failed.size());

        if (failed.size() > 0) {
            ProposalResponse first = failed.iterator().next();
            ErrorInfo result = new ErrorInfo(500, "", "Not enough endorsers for install :" + successful.size() + ".  " + first.getMessage());
            return result;
        }

        ErrorInfo result = new ErrorInfo(0, "", "OK");
        return result;
    }

    // save the CC invoke TxID and use in queries
    String testTxID = null;

    private ErrorInfo instantiatechaincode() throws InvalidArgumentException, IOException, ChaincodeEndorsementPolicyParseException, ProposalException, InterruptedException, ExecutionException, TimeoutException {

        final ChainCodeID chainCodeID;
        Collection<ProposalResponse> responses;
        Collection<ProposalResponse> successful = new LinkedList<>();
        Collection<ProposalResponse> failed = new LinkedList<>();
        Collection<Orderer> orderers = chain.getOrderers();

        chainCodeID = ChainCodeID.newBuilder().setName(CHAIN_CODE_NAME)
                .setVersion(CHAIN_CODE_VERSION)
                .setPath(CHAIN_CODE_PATH).build();

        SampleOrg sampleOrg = TESTCONFIG.getIntegrationTestsSampleOrg("peerOrg1");
        client.setUserContext(sampleOrg.getPeerAdmin());

        // Instantiate chain code.
        InstantiateProposalRequest instantiateProposalRequest = client.newInstantiationProposalRequest();
        instantiateProposalRequest.setProposalWaitTime(60000);
        instantiateProposalRequest.setChaincodeID(chainCodeID);
        instantiateProposalRequest.setFcn("init");
        instantiateProposalRequest.setArgs(new String[]{"999"});
        Map<String, byte[]> tm = new HashMap<>();
        tm.put("HyperLedgerFabric", "InstantiateProposalRequest:JavaSDK".getBytes(UTF_8));
        tm.put("method", "InstantiateProposalRequest".getBytes(UTF_8));
        instantiateProposalRequest.setTransientMap(tm);

        // policy OR(Org1MSP.member, Org2MSP.member) meaning 1 signature from someone in either Org1 or Org2
        // See README.md Chaincode endorsement policies section for more details.
        ChaincodeEndorsementPolicy chaincodeEndorsementPolicy = new ChaincodeEndorsementPolicy();
        chaincodeEndorsementPolicy.fromYamlFile(new File(PROJECT_ROOT + "/fixtures/chaincodeendorsementpolicy.yaml"));
        instantiateProposalRequest.setChaincodeEndorsementPolicy(chaincodeEndorsementPolicy);

        out("Sending instantiateProposalRequest to all peers.");
        successful.clear();
        failed.clear();

        responses = chain.sendInstantiationProposal(instantiateProposalRequest, chain.getPeers());
        for (ProposalResponse response : responses) {
            if (response.isVerified() && response.getStatus() == ProposalResponse.Status.SUCCESS) {
                successful.add(response);
                out("Succesful instantiate proposal response Txid: %s from peer %s", response.getTransactionID(), response.getPeer().getName());
            } else {
                failed.add(response);
            }
        }
        out("Received %d instantiate proposal responses. Successful+verified: %d . Failed: %d", responses.size(), successful.size(), failed.size());
        if (failed.size() > 0) {
            ProposalResponse first = failed.iterator().next();
            ErrorInfo result = new ErrorInfo(500, "", "Not enough endorsers for instantiate :" + successful.size() + "endorser failed with " + first.getMessage() + ". Was verified:" + first.isVerified());
            return result;
        }

        // Send instantiate transaction to orderer
        out("Sending instantiateTransaction to orderer.");
        chain.sendTransaction(successful, orderers).thenApply(transactionEvent -> {
            out("Finished instantiate transaction with transaction id %s", transactionEvent.getTransactionID());
            return null;
        }).exceptionally(e -> {
            if (e instanceof TransactionEventException) {
                BlockEvent.TransactionEvent te = ((TransactionEventException) e).getTransactionEvent();
                if (te != null) {
                    ErrorInfo result = new ErrorInfo(500, "", "Transaction with txid %s failed. %s" + te.getTransactionID() + e.getMessage());
                    return result;
                }
            }

            ErrorInfo result = new ErrorInfo(500, "", e.getMessage());
            return result;
        }).get(TESTCONFIG.getTransactionWaitTime(), TimeUnit.SECONDS);

        ErrorInfo result = new ErrorInfo(0, "", "OK");
        return result;
    }

    static void out(String format, Object... args) {
        System.err.flush();
        System.out.flush();
        System.out.println(format(format, args));
        System.err.flush();
        System.out.flush();
    }

}
