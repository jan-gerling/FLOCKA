#!/bin/bash

sbt assembly
docker build -t service-runner .
export SEED0_IP=localhost; export MY_IP=localhost; docker-compose -f docker-compose-local.yml up
