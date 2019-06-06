#!/bin/sh

docker run wdm2019akka/service-runner u -Dakka.cluster.seed-nodes.0=akka.tcp://flocka-user@$SEED0_IP:2551  -Dakka.remote.netty.tcp.hostname=$(curl http://169.254.169.254/latest/meta-data/local-ipv4) -Dakka.remote.netty.tcp.port=2551 -Dloadbalancer.user.uri=$USER_LB_URI -Dloadbalancer.stock.uri=$STOCK_LB_URI -Dloadbalancer.order.uri=$ORDER_LB_URI -Dloadbalancer.payment.uri=$PAYMENT_LB_URI -Dakka.contrib.persistence.mongodb.mongo.mongouri=$MONGO_URI
