#!/bin/bash

sbt assembly
docker build -t service-runner-mongo .
export SEED0_IP=localhost; export MY_IP=localhost; docker-compose -f docker-compose-with-mongo.yml up
