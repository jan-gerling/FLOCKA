# FLOCKA

Welcome to our charming project, **we love** to see **you** here!
If you have any suggestions or comments please post them here [#2](https://github.com/jan-gerling/lam-dal/issues/2).

Check this [issue](https://github.com/jan-gerling/lam-dal/issues/7) to get familiar with the basics for this project.


## Save these Dates

- **Tuesday** morning from 09:30 till 12:30 - **Team Meeting** and **sprint ending**
- Wednesday morning from 09:00 till 10:45 - additional Team Meeting or backup
- **21st May** - Milestone 01 - feature complete **protoype**
- **28th May** - Milestone 02 - **first iteration** with stress testing and improvements
- **05th June** - Milestone 03 - **final deliverable**



## Guidelines 

There are a few guidelines for our project please follow them it will make us all happy .

- **Team**
  - **Communicate** with us! Having any issues? Made good progress? Having a good idea or day? We would **love** to know!
  - **Attend the weekly meetings**, inform in advance on any issues. We will find a solution. :)
- **Github**
  - **Do not push to master,** use [pull requests](<https://help.github.com/en/articles/about-pull-requests>) to get your code into master
  - There is an issue for every feature or vice versa: **issue <=> feature**
  - Create a new branch **pull request for every feature**. Do not make any changes not related to the current feature.
  - **Prepare** your **pull requests**, see below.
  - Write reasonable git [commit messages] (<https://chris.beams.io/posts/git-commit/>)

## Executing SBT

Make sure sbt is executable:
```
chmod u+x ./sbt
chmod u+x ./sbt-dist/bin/sbt

#I guess you may also want to add sbt to your PATH permanently
echo "alias sbt='/path/to/project/sbt'"
```

Some sbt commands:
* sbt -- Runs an interactive sbt shell
* sbt compile -- Compiles the project
* sbt run -- Runs the project
* sbt test -- Tests the project

Works similarly to Maven in the sense that targets will execute previous targets (e.g. test will compile if needed)

## Port scheme ##
REST Endpoint
Users: 8080
Stock: 8081
Order: 8082
Payment: 8083

Cluster seed nodes
Users: 2551
Stock: 2561
Order: 2571
Payment: 2581

## Deployment

Current deployment ideal involves a load-balancer per service capable of accessing the state of an AWS Autoscaling group, such that it can route to any node in said group.

To build our own Amazon Image, we need a base image (ubuntu or whatever), install docker and docker-compose. Run docker as a service. 

### Deployment Details

Dockerfile -- Copies into a java:8 base image our jar and run.sh. run.sh is the entrypoint

run.sh -- Script that runs our jar, passing the first argument given to docker to our jar, and all others to java. This way we can override configuration at launch

docker-compose-local.yml -- Runs each service, as well as mongo for local testing.

docker-compose-deploy.yml -- Runs each service, in deployment mode.

build_and_run.sh -- Will build the jar and docker image locally, running docker-compose automatically.
run_X_service.sh -- runs a specific service, needs ENV VARS:
* $SEED0_IP -- The IP of the Seed node
* $X_LB_URI -- LB IP of each service, necessary
* $MONGO_URI -- URI of mongoDB

Use internal IPs where possible

## Docker credentials
User: wdm2019akka
PW: WakkaFlocka
image: wdm2019akka/service-runner

## Local deployment for testing

Im assuming the node running mongo is the seed for all other nodes

### Run the mongo node:

export MY_IP=<Your internal IP> ; export SEED0_IP=<Your internal IP> ; docker-compose -f docker-compose-with-mongo.yml up

### Run connecting nodes:

export MY_IP=<Your internal IP> ; export SEED0_IP=<SEED0IP> ; export MONGO_URI=mongodb://root:example@<SEED0IP>:27017/admin ; docker-compose -f docker-compose-without-mongo.yml up

##AWS deployment

Aws deployment is using DynamoDB:

USER_LB_URI=http://UserLB-78627843.eu-central-1.elb.amazonaws.com:8080
STOCK_LB_URI=http://StockLB-1528496191.eu-central-1.elb.amazonaws.com:8081
ORDER_LB_URI=http://OrderLB-2122101751.eu-central-1.elb.amazonaws.com:8082
PAYMENT_LB_URI=http://PaymentLB-1003200854.eu-central-1.elb.amazonaws.com:8083
