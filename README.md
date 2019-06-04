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

##Stress Testing ##
* go to /locust-test
* execute in terminal:
```
locust --host=http://127.0.0.1
```
You can access the interface at <http://localhost:8089/>