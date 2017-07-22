package main

import (
	"encoding/json"
	"errors"
	"fmt"

	"github.com/hyperledger/fabric/core/chaincode/shim"
	pb "github.com/hyperledger/fabric/protos/peer"
)

/*
 * Checks the car numberplate.
 *
 * The numberplate is handed out by the DOT.
 */
func IsConfirmed(car *Car) bool {
	// cannot have a numberplate without car papers
	if !IsRegistered(car) {
		return false
	}

	// cannot give you a numberplate without insurance contract
	if !IsInsured(car) {
		return false
	}

	confirmed := car.Certificate.Numberplate != ""

	// because the car is registered, the car VIN can be trusted
	if confirmed {
		fmt.Printf("Car with VIN '%s' is confirmed\n", car.Vin)
	} else {
		fmt.Printf("Car with VIN '%s' has no valid numberplate\n", car.Vin)
	}

	return confirmed
}

/*
 * Checks for a valid car VIN.
 *
 * The car VIN is valid if the DOT certificate contains
 * the same information. Registration guarantees that
 * certificate VIN and a car VIN are equal and that
 * a certificate was issued by the DOT at least once.
 */
func IsRegistered(car *Car) bool {
	// cannot be registered without certificate
	if car.Certificate.Vin == "" {
		fmt.Printf("Car created at ts '%d' is not yet registered\n", car.CreatedTs)
		return false
	}

	// validate car VIN
	carVin := car.Vin
	registered := car.Certificate.Vin == carVin

	if registered {
		fmt.Printf("Car created at ts '%d' is registered with VIN '%s'\n", car.CreatedTs, carVin)
	} else {
		fmt.Printf("Car created at ts '%d' is not yet registered\n", car.CreatedTs)
	}

	return registered
}

/*
 * Returns the registration proposal index with all
 * registration proposals.
 */
func (t *CarChaincode) getRegistrationProposals(stub shim.ChaincodeStubInterface) (map[string]RegistrationProposal, error) {
	response := t.read(stub, registrationProposalIndexStr)
	proposalIndex := make(map[string]RegistrationProposal)
	err := json.Unmarshal(response.Payload, &proposalIndex)
	if err != nil {
		return nil, errors.New("Error parsing registration proposal index")
	}

	return proposalIndex, nil
}

/*
 * Reads all registration proposals.
 */
func (t *CarChaincode) readRegistrationProposals(stub shim.ChaincodeStubInterface) pb.Response {
	proposalIndex, err := t.getRegistrationProposals(stub)
	if err != nil {
		return shim.Error("Error reading registration proposal index")
	}

	indexAsBytes, _ := json.Marshal(proposalIndex)
	return shim.Success(indexAsBytes)
}

/*
 * Reads all registration proposals and returns them as an array
 */
func (t *CarChaincode) readRegistrationProposalsList(stub shim.ChaincodeStubInterface) pb.Response {
	proposalIndex, err := t.getRegistrationProposals(stub)
	if err != nil {
		return shim.Error("Error reading registration proposal index")
	}
	var registrationProposalList []RegistrationProposal

	for _, v := range proposalIndex {
		registrationProposalList = append(registrationProposalList, v)
	}

	indexListAsBytes, _ := json.Marshal(registrationProposalList)
	return shim.Success(indexListAsBytes)
}

/*
 * Returns a registration proposal for a car.
 */
func (t *CarChaincode) getRegistrationProposal(stub shim.ChaincodeStubInterface, car string) pb.Response {
	// load all proposals
	proposalIndex, err := t.getRegistrationProposals(stub)
	if err != nil {
		return shim.Error("Error reading registration proposal index")
	}

	ret := proposalIndex[car]
	retAsBytes, _ := json.Marshal(ret)
	return shim.Success(retAsBytes)
}

/*
 * Registers a car.
 *
 * Registration guarantees that certificate VIN
 * and a car VIN are equal and that a certificate
 * was issued by the DOT at least once.
 *
 * To registerCar a car, a RegistrationProposal needs to be present.
 * This proposal is removed/deleted after successfull registration.
 * This is not consistent with reality, but serves the purpose
 * for now, because the Form 13.20 A (RegistrationProposal)
 * is not used anywhere else right now. Like this, the RegistrationProposal
 * only serves the purpose to signal the DOT, that there is a new
 * car waiting for registration.
 *
 * On success,
 * returns the car with certificate.
 */
func (t *CarChaincode) registerCar(stub shim.ChaincodeStubInterface, username string, vin string) pb.Response {
	// reading the car already checks that the user
	// is the actual owner of the car
	car, err := t.getCarAsDot(stub, vin)
	if err != nil {
		return shim.Error(fmt.Sprintf("Error getting car as DOT"))
	}
	if vin != car.Vin {
		return shim.Error(fmt.Sprintf("Cannot register, invalid VIN.\nCar VIN is '%s' and you want to register VIN '%s'", car.Vin, vin))
	}

	// get all registration proposals
	proposals, err := t.getRegistrationProposals(stub)
	if err != nil {
		return shim.Error("Error reading registration proposal index")
	}

	// check if there exists a registration proposal for that car
	if proposals[car.Vin].Car != vin {
		return shim.Error(fmt.Sprintf("There exists no registration proposal for car with VIN: %s", vin))
	}

	// create a certificate, approve vin
	// and update the car in the ledger
	car.Certificate.Username = username
	car.Certificate.Vin = vin
	carAsBytes, _ := json.Marshal(car)
	err = stub.PutState(car.Vin, carAsBytes)
	if err != nil {
		return shim.Error("Error writing car")
	}

	// remove the proposal we just registered
	delete(proposals, car.Vin)

	// save the new proposal index
	// without the car we just registered
	proposalsAsBytes, _ := json.Marshal(proposals)
	err = stub.PutState(registrationProposalIndexStr, proposalsAsBytes)
	if err != nil {
		return shim.Error("Error writing proposal index")
	}

	fmt.Printf("Successfully registered car created at ts '%d' with VIN '%s'\n", car.CreatedTs, vin)

	return shim.Success(carAsBytes)
}

/*
 * Returns a list of cars to be confirmed
 *
 * On success,
 * returns a list with cars to confirm.
 */
func (t *CarChaincode) getCarsToConfirm(stub shim.ChaincodeStubInterface) pb.Response {
	carIndex, err := t.getCarIndex(stub)

	if err != nil {
		return shim.Error("Error getting car index")
	}

	var toConfirmcarList []Car

	for k := range carIndex {
		car, err := t.getCarAsDot(stub, k)
		if err != nil {
			return shim.Error("Error getting car")
		}
		if IsRegistered(&car) && IsInsured(&car) {
			toConfirmcarList = append(toConfirmcarList, car)
		}
	}

	// marshal list
	toConfirmcarListAsBytes, _ := json.Marshal(toConfirmcarList)

	// car confirmation successfull,
	// return the car with numberplate
	return shim.Success(toConfirmcarListAsBytes)
}

/*
 * Confirms a car and assigns a numberplate.
 *
 * Only the owner of a car can request confirmation of a car.
 * Car needs to be insured as a requirement for getting
 * the permit to drive on the roads. Only insured cars can get
 * confirmed and get a numberplate.
 *
 * Required arguments:
 *   [0] Vin         (string)
 *   [1] Numberplate (string)
 *
 * On success,
 * returns the car with numberplate.
 */
func (t *CarChaincode) confirmCar(stub shim.ChaincodeStubInterface, username string, args []string) pb.Response {
	vin := args[0]
	numberplate := args[1]

	if vin == "" {
		return shim.Error("'confirm' expects a non-empty VIN to assign a numberplate")
	}

	// check numberplate argument
	if numberplate == "" {
		return shim.Error("Car numberplate is empty. Please provide a numberplate to confirm your car")
	}

	// fetch the car from the ledger
	// this already checks for ownership
	car, err := t.getCar(stub, username, vin)
	if err != nil {
		return shim.Error("Failed to fetch car with vin '" + vin + "' from ledger")
	}

	// check if car is insured
	if !IsInsured(&car) {
		return shim.Error("Car is not insured. Please insure car first before trying to confirm it")
	}

	// check if numberplate is already in use
	carIndex, err := t.getCarIndex(stub)
	carToCheck := Car{}
	for carVin, user := range carIndex {
		// get the full car object with certificate
		carToCheck, err = t.getCar(stub, user, carVin)
		if err != nil {
			return shim.Error("Failed to fetch car with vin '" + carVin + "' from ledger")
		}

		if carToCheck.Certificate.Numberplate == numberplate {
			return shim.Error("Car numberplate already in use. Please use another one!")
		}
	}

	// assign the numberplate to the car
	car.Certificate.Numberplate = numberplate

	// write udpated car back to ledger
	carAsBytes, _ := json.Marshal(car)
	err = stub.PutState(vin, carAsBytes)
	if err != nil {
		return shim.Error("Error writing car")
	}

	// car confirmation successfull,
	// return the car with numberplate
	return shim.Success(carAsBytes)
}

/*
 * Revokes a car.
 *
 * Only the owner of a car can request revocation of a car.
 * A revocation will render the numberplate
 * and the insurance contract as invalid.
 * This is required before a car transfer.
 *
 * On success,
 * returns the car.
 */
func (t *CarChaincode) revoke(stub shim.ChaincodeStubInterface, username string, vin string) pb.Response {
	if vin == "" {
		return shim.Error("'revoke' expects a non-empty VIN to do the revocation")
	}

	// fetch the car from the ledger
	// this already checks for ownership
	car, err := t.getCar(stub, username, vin)
	if err != nil {
		return shim.Error("Failed to fetch car with vin '" + vin + "' from ledger")
	}

	// remove car insurance
	car.Certificate.Insurer = ""

	// check if car is not anymore insured
	if IsInsured(&car) {
		return shim.Error("Whoops... Something went wrong while revoking car. Car is still insured.")
	}

	// remove numberplate
	car.Certificate.Numberplate = ""

	// check if not confirmed anymore
	if IsConfirmed(&car) {
		return shim.Error("Whoops... Something went wrong while revoking car. Car is still confirmed.")
	}

	// write udpated car back to ledger
	carAsBytes, _ := json.Marshal(car)
	err = stub.PutState(vin, carAsBytes)
	if err != nil {
		return shim.Error("Error writing car")
	}

	// fetch all revocation proposals
	response := t.getRevocationProposals(stub)
	index := make(map[string]string)
	err = json.Unmarshal(response.Payload, &index)
	if err != nil {
		return shim.Error("Failed to fetch revocation proposals")
	}

	// remove the revocation proposal if any
	delete(index, car.Vin)

	// save proposals back to ledger
	indexAsBytes, _ := json.Marshal(index)
	err = stub.PutState(revocationProposalIndexStr, indexAsBytes)
	if err != nil {
		return shim.Error("Error writing revocation proposals")
	}

	// car revokation successfull,
	// return the car
	return shim.Success(carAsBytes)
}

/*
 * Returns all revocation proposals.
 */
func (t *CarChaincode) getRevocationProposals(stub shim.ChaincodeStubInterface) pb.Response {
	response := t.read(stub, revocationProposalIndexStr)
	index := make(map[string]string)
	err := json.Unmarshal(response.Payload, &index)
	if err != nil {
		return shim.Error("Error reading revocation proposal index")
	}

	return shim.Success(response.Payload)
}

/*
 * Creates a revocation proposal.
 *
 * Only the owner of a car can request revocation of a car.
 * A revocation proposal is not a prerequisite, for the DOT
 * to revoke a car. A car could be revoked inedependently
 * of this proposal.
 *
 * On success,
 * returns 'nil'.
 */
func (t *CarChaincode) revocationProposal(stub shim.ChaincodeStubInterface, username string, vin string) pb.Response {
	if vin == "" {
		return shim.Error("'revocationProposal' expects a non-empty VIN to do the revocation")
	}

	// fetch the car from the ledger
	// this already checks for ownership
	car, err := t.getCar(stub, username, vin)
	if err != nil {
		return shim.Error("Failed to fetch car with vin '" + vin + "' from ledger")
	}

	// check if the car can be revoked
	if !IsConfirmed(&car) {
		return shim.Error("You cannot create a revocation proposal for an unconfirmed car.")
	}

	// fetch all the revocation proposals
	response := t.read(stub, revocationProposalIndexStr)
	index := make(map[string]string)
	err = json.Unmarshal(response.Payload, &index)
	if err != nil {
		return shim.Error("Error parsing revocation proposal index")
	}

	// check if a proposal to revoke this car already exists
	if index[vin] == username {
		return shim.Error("A revocation proposal for that car VIN and user already exists.")
	}

	// save the users request to revok his car
	// in the revocation proposal index
	index[vin] = username

	// save index back to ledger
	indexAsBytes, _ := json.Marshal(index)
	err = stub.PutState(revocationProposalIndexStr, indexAsBytes)
	if err != nil {
		return shim.Error("Error writing revocation proposal index")
	}

	return shim.Success(nil)
}

/*
 * Deletes a car from the ledger.
 *
 * Returns 'nil' on success.
 */
func (t *CarChaincode) deleteCar(stub shim.ChaincodeStubInterface, vin string) pb.Response {
	//getting car index
	carIndex, err := t.getCarIndex(stub)
	if err != nil {
		return shim.Error("Could not get car index")
	}
	_, carExisting := carIndex[vin]
	if !carExisting {
		return shim.Error("Car does not exist in Car Index!")
	}
	delete(carIndex, vin)

	// Delete the key from the state in ledger
	err = stub.DelState(vin)
	if err != nil {
		return shim.Error("Failed to delete car state")
	}

	fmt.Printf("Successfully deleted car with VIN: '%s'\n", vin)
	return shim.Success(nil)
}
