package main

import (
    "fmt"
    "encoding/json"
    "time"
    "errors"

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
 * Creates a new, unregistered car with the current timestamp
 * and appends it to the car index. Returns an error if a
 * car with the desired VIN already exists.
 *
 * Expects 'args':
 *  Car with VIN        json
 *
 * On success,
 * returns the car.
 */
func (t *CarChaincode) create(stub shim.ChaincodeStubInterface, username string, args []string) pb.Response {
    if len(args) != 1 {
        return shim.Error("'create' expects Car with VIN as json")
    }

    // create car from arguments
    car := Car {}
    err := json.Unmarshal([]byte(args[0]), &car)
    if err != nil {
        return shim.Error("Error parsing car data. Expecting Car with VIN as json.")
    }

    // add car birth date
    car.CreatedTs = time.Now().Unix()

    // create user from arguments
    user := User {}

    // check for existing garage user with that name
    response := t.read(stub, username)
    existingUser := User {}
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
    car := Car {}
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