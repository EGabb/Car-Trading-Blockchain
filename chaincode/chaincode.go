package main

import (
    "fmt"
    "strconv"
    "strings"

    "github.com/hyperledger/fabric/core/chaincode/shim"
    pb "github.com/hyperledger/fabric/protos/peer"
)

type CarChaincode struct {
}

const carIndexStr string = "_cars"

func (t *CarChaincode) Init(stub shim.ChaincodeStubInterface) pb.Response {
    fmt.Println("Car demo Init")
    
    var aval int
    var err error

    _, args := stub.GetFunctionAndParameters()
    if len(args) != 1 {
        return shim.Error("Incorrect number of arguments. Expecting 1 integer to test chain.")
    }

    // initialize the chaincode
    aval, err = strconv.Atoi(args[0])
    if err != nil {
        return shim.Error("Expecting integer value for asset holding")
    }

    // write the state to the ledger
    // make a test var "abc" in order to able to query it and see if it worked
    err = stub.PutState("abc", []byte(strconv.Itoa(aval)))
    if err != nil {
        return shim.Error(err.Error())
    }
 
    // clear the car index
    err = clearCarIndex(carIndexStr, stub)
    if err != nil {
        return shim.Error(err.Error())
    }

    fmt.Println("Car index clean")
    fmt.Println("Init terminated")
    return shim.Success(nil)
}

func (t *CarChaincode) Invoke(stub shim.ChaincodeStubInterface) pb.Response {
    function, args := stub.GetFunctionAndParameters()
    fmt.Println("Invoke is running function '" + function + "' with args: " + strings.Join(args, ", "))

    if function == "create" {
        return t.create(stub, args)
    } else if function == "read" {
        if len(args) != 1 {
            return shim.Error("'read' expects exactly one key to do the look up")
        } else {
            return t.read(stub, args[0])
        }
    }

    return shim.Error("Invoke did not find function: " + function)
}

/*
 * Reads ledger state from position 'key'.
 *
 * Can be any of:
 *  - Car   (expects car timestamp as key)
 *  - User  (expects user name as key)
 *  - or an index like '_cars'
 *
 * On success,
 * returns ledger state in bytes at position 'key'.
 */
func (t *CarChaincode) read(stub shim.ChaincodeStubInterface, key string) pb.Response {
    if key == "" {
        return shim.Error("'read' expects a non-empty key to do the look up")
    }

    valAsBytes, err := stub.GetState(key)
    if err != nil {
        return shim.Error("Failed to fetch value at key '" + key + "' from ledger")
    }

    return shim.Success(valAsBytes)
}

func main() {
    err := shim.Start(new(CarChaincode))
    if err != nil {
        fmt.Printf("Error starting Simple chaincode: %s", err)
    }
}