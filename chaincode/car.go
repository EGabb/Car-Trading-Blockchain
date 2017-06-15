package main

import (
    "encoding/json"
    "errors"
    "fmt"
    "time"

    "github.com/hyperledger/fabric/core/chaincode/shim"
    pb "github.com/hyperledger/fabric/protos/peer"
)

/*
 * Returns the car index
 */
func (t *CarChaincode) getCarIndex(stub shim.ChaincodeStubInterface) (map[string]string, error) {
    response := t.read(stub, carIndexStr)
    carIndex := make(map[string]string)
    err := json.Unmarshal(response.Payload, &carIndex)
    if err != nil {
        return nil, errors.New("Error parsing car index")
    }

    return carIndex, nil
}

/*
 * Reads the car index at key 'vin'
 *
 * Returns username of car owner with VIN 'vin'.
 */
func (t *CarChaincode) getOwner(stub shim.ChaincodeStubInterface, vin string) (string, error) {
    carIndex, err := t.getCarIndex(stub)
    if err != nil {
        return "", err
    }
    return carIndex[vin], nil
}

/*
 * Reads a User from ledger
 */
func (t *CarChaincode) getUser(stub shim.ChaincodeStubInterface, username string) (User, error) {
    response := t.read(stub, username)
    user := User {}
    err := json.Unmarshal(response.Payload, &user)
    if err != nil {
        return User {}, errors.New("Error fetching User")
    }

    return user, nil
}

/*
 * Creates a new, unregistered car with the current timestamp
 * and appends it to the car index. Returns an error if a
 * car with the desired VIN already exists.
 *
 * A registration proposal will be issued on successfull car creation.
 * For this proposal, optional registration data can be passed to
 * 'create' to create a tailored registration proposal.
 *
 * Expects 'args':
 *  Car with VIN                             json
 *  (optional) RegistrationProposal          json
 *
 * On success,
 * returns the car.
 */
func (t *CarChaincode) create(stub shim.ChaincodeStubInterface, username string, args []string) pb.Response {
    if len(args) < 1 {
        return shim.Error("'create' expects Car with VIN as json")
    }

    // create new registration proposal for the DOT
    regProposal := RegistrationProposal{}

    // if provided, read additional registration data
    if len(args) > 1 {
        fmt.Printf("Received registration data: %s\n", args[1])
        err := json.Unmarshal([]byte(args[1]), &regProposal)
        if err != nil {
            fmt.Println("Unable to parse your registration data")
        }
    }

    // let the invoker know if his data was well formatted
    fmt.Printf("Creating car with parsed registration proposal: %v\n", regProposal)

    // create car from arguments
    car := Car{}
    err := json.Unmarshal([]byte(args[0]), &car)
    if err != nil {
        return shim.Error("Error parsing car data. Expecting Car with VIN as json.")
    }

    // add car birth date
    car.CreatedTs = time.Now().Unix()

    // create user from arguments
    user := User{}

    // check for existing garage user with that name
    response := t.read(stub, username)
    existingUser := User{}
    err = json.Unmarshal(response.Payload, &existingUser)
    if err == nil {
        user = existingUser
    } else {
        user.Name = username
    }

    // check for an existing car with that vin in the car index
    owner, err := t.getOwner(stub, car.Vin)
    if err != nil {
        return shim.Error(err.Error())
    } else if owner != "" {
        return shim.Error(fmt.Sprintf("Car with vin '%s' already exists. Choose another vin.", car.Vin))
    }

    // save car to ledger, the car vin serves
    // as the index to find the car again
    carAsBytes, _ := json.Marshal(car)
    err = stub.PutState(car.Vin, carAsBytes)
    if err != nil {
        return shim.Error("Error writing car")
    }

    // map the car to the users name
    carIndex, err := t.getCarIndex(stub)
    if err != nil {
        return shim.Error(err.Error())
    }
    carIndex[car.Vin] = user.Name
    fmt.Printf("Added car with VIN '%s' created at '%d' in garage '%s' to car index.\n",
        car.Vin, car.CreatedTs, user.Name)

    // write udpated car index back to ledger
    indexAsBytes, _ := json.Marshal(carIndex)
    err = stub.PutState(carIndexStr, indexAsBytes)
    if err != nil {
        return shim.Error("Error writing car index")
    }

    // hand over the car and write user to ledger
    user.Cars = append(user.Cars, car.Vin)
    userAsBytes, _ := json.Marshal(user)
    err = stub.PutState(user.Name, userAsBytes)
    if err != nil {
        return shim.Error("Error writing user")
    }

    // load all proposals
    proposalIndex, err := t.getRegistrationProposals(stub)
    if err != nil {
        return shim.Error("Error loading registration proposal index")
    }

    // update the car vin in the registration proposal
    // and save the proposal for the DOT
    regProposal.Car = car.Vin
    proposalIndex[car.Vin] = regProposal

    // write udpated proposal index back to ledger
    // for the DOT to read and register the car
    indexAsBytes, _ = json.Marshal(proposalIndex)
    err = stub.PutState(registrationProposalIndexStr, indexAsBytes)
    if err != nil {
        return shim.Error("Error writing registration proposal index")
    }

    // car creation successfull,
    // return the car
    return shim.Success(carAsBytes)
}

/*
 * Reads a car.
 *
 * Only the car owner can read the car.
 *
 * On success,
 * returns the car.
 */
func (t *CarChaincode) readCar(stub shim.ChaincodeStubInterface, username string, vin string) pb.Response {
    if vin == "" {
        return shim.Error("'readCar' expects a non-empty VIN to do the look up")
    }

    // fetch the car from the ledger
    carResponse := t.read(stub, vin)
    car := Car{}
    err := json.Unmarshal(carResponse.Payload, &car)
    if err != nil {
        return shim.Error("Failed to fetch car with vin '" + vin + "' from ledger")
    }

    // fetch the car index to check if the user owns the car
    owner, err := t.getOwner(stub, vin)
    if err != nil {
        return shim.Error(err.Error())
    } else if owner != username {
        return shim.Error("Forbidden: this is not your car")
    }

    return shim.Success(carResponse.Payload)
}

/*
 * Transfers a car to a new owner (receiver)
 * 
 * Arguments required:
 * [0] Username                    (string)
 * [1] Username of ther receiver   (string)
 *
 * On success,
 * returns the car.
 */
func (t *CarChaincode) transfer(stub shim.ChaincodeStubInterface, username string, args []string) pb.Response {
    vin := args[0]
    newCarOwner := args[1]

    if vin == "" {
        return shim.Error("'transfer' expects a non-empty VIN to do the transfer")
    }

    if newCarOwner == "" {
        return shim.Error("'transfer' expects a non-empty car receiver username to do the transfer")
    }

    // fetch the car from the ledger
    // this already checks for ownership
    carResponse := t.readCar(stub, username, vin)
    car := Car{}
    err := json.Unmarshal(carResponse.Payload, &car)
    if err != nil {
        return shim.Error("Failed to fetch car with vin '" + vin + "' from ledger")
    }

    // check if car is not confirmed anymore
    if IsConfirmed(&car) {
        return shim.Error("The car is still confirmed. It has to be revoked first in order to do the transfer")
    }

    // transfer:
    // change of ownership in the car certificate
    car.Certificate.Username = newCarOwner

    // write car with udpated certificate back to ledger
    carAsBytes, _ := json.Marshal(car)
    err = stub.PutState(vin, carAsBytes)
    if err != nil {
        return shim.Error("Error writing car")
    }

    // get the old car owner
    owner, err := t.getUser(stub, username)
    if err != nil {
        return shim.Error("Error fetching old car owner")
    }

    // go through all his cars
    // and remove the car we just transferred
    cars := owner.Cars
    var newCarList []string
    for i, carVin := range cars {
        newCarList = append(newCarList, carVin)
        if carVin == car.Vin {
            // remove car from new list
            newCarList = newCarList[:i]
        }
    }

    // save the new car list without the transferred
    // car to the old owner
    owner.Cars = newCarList

    // write the old owner back to state
    ownerAsBytes, _ := json.Marshal(owner)
    err = stub.PutState(username, ownerAsBytes)
    if err != nil {
        return shim.Error("Error writing old owner")
    }

    // get the receiver of the car
    // (new car owner)
    owner, err = t.getUser(stub, newCarOwner)
    if err != nil {
        return shim.Error("Error fetching new car owner (receiver)")
    }

    // attach the car to the receiver (new car owner)
    owner.Cars = append(owner.Cars, car.Vin)

    // write back the new owner (reveiver) to state
    ownerAsBytes, _ = json.Marshal(owner)
    err = stub.PutState(newCarOwner, ownerAsBytes)
    if err != nil {
        return shim.Error("Error writing new car owner (receiver)")
    }

    // car transfer successfull,
    // return the car
    return shim.Success(carAsBytes)
}