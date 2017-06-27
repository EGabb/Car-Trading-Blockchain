#!/usr/bin/env bash

ARCH=x86_64
BASE_VERSION=1.0.0-preview
PROJECT_VERSION=1.0.0-preview
IMG_VERSION=0.9.3

echo "Building peer based on yeasy image..."
docker build -t egabb/hyperledger-fabric-peer:$IMG_VERSION .

echo "Downloading yeasy images from DockerHub..."
docker pull yeasy/hyperledger-fabric-base:$IMG_VERSION \
  && docker pull yeasy/hyperledger-fabric-orderer:$IMG_VERSION \
  && docker pull yeasy/hyperledger-fabric-ca:$IMG_VERSION

# Only useful for debugging
# docker pull yeasy/hyperledger-fabric

echo "Rename yeasy images with official tags..."
docker tag egabb/hyperledger-fabric-peer:$IMG_VERSION hyperledger/fabric-peer \
  && docker tag yeasy/hyperledger-fabric-orderer:$IMG_VERSION hyperledger/fabric-orderer \
  && docker tag yeasy/hyperledger-fabric-ca:$IMG_VERSION hyperledger/fabric-ca \
  && docker tag yeasy/hyperledger-fabric-base:$IMG_VERSION hyperledger/fabric-ccenv:$ARCH-$BASE_VERSION \
  && docker tag yeasy/hyperledger-fabric-base:$IMG_VERSION hyperledger/fabric-baseos:$ARCH-$BASE_VERSION
