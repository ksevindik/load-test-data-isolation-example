#!/bin/bash

# Wait for Kafka to be ready
echo "Waiting for Kafka to be ready..."
cub kafka-ready -b localhost:9092 1 60

echo "Creating SCRAM users..."

# Create admin user
kafka-configs --bootstrap-server localhost:9092 --alter --add-config 'SCRAM-SHA-256=[password=admin_secret]' --entity-type users --entity-name admin

# Create producer users
kafka-configs --bootstrap-server localhost:9092 --alter --add-config 'SCRAM-SHA-256=[password=prod_producer_secret]' --entity-type users --entity-name prod_producer
kafka-configs --bootstrap-server localhost:9092 --alter --add-config 'SCRAM-SHA-256=[password=test_producer_secret]' --entity-type users --entity-name test_producer

# Create consumer users
kafka-configs --bootstrap-server localhost:9092 --alter --add-config 'SCRAM-SHA-256=[password=prod_consumer_secret]' --entity-type users --entity-name prod_consumer
kafka-configs --bootstrap-server localhost:9092 --alter --add-config 'SCRAM-SHA-256=[password=test_consumer_secret]' --entity-type users --entity-name test_consumer

echo "Creating topics..."

# Create topics
kafka-topics --bootstrap-server localhost:9092 --create --if-not-exists --topic user-events.real --partitions 1 --replication-factor 1
kafka-topics --bootstrap-server localhost:9092 --create --if-not-exists --topic user-events.test --partitions 1 --replication-factor 1

echo "Setting up ACLs..."

# ACLs for prod_producer - Write to user-events.real only
kafka-acls --bootstrap-server localhost:9092 --add --allow-principal User:prod_producer --operation Write --topic user-events.real
kafka-acls --bootstrap-server localhost:9092 --add --allow-principal User:prod_producer --operation Describe --topic user-events.real

# ACLs for test_producer - Write to user-events.test only
kafka-acls --bootstrap-server localhost:9092 --add --allow-principal User:test_producer --operation Write --topic user-events.test
kafka-acls --bootstrap-server localhost:9092 --add --allow-principal User:test_producer --operation Describe --topic user-events.test

# ACLs for prod_consumer - Read from user-events.real only
kafka-acls --bootstrap-server localhost:9092 --add --allow-principal User:prod_consumer --operation Read --topic user-events.real
kafka-acls --bootstrap-server localhost:9092 --add --allow-principal User:prod_consumer --operation Describe --topic user-events.real
kafka-acls --bootstrap-server localhost:9092 --add --allow-principal User:prod_consumer --operation Read --group prod-consumer-group

# ACLs for test_consumer - Read from user-events.test only
kafka-acls --bootstrap-server localhost:9092 --add --allow-principal User:test_consumer --operation Read --topic user-events.test
kafka-acls --bootstrap-server localhost:9092 --add --allow-principal User:test_consumer --operation Describe --topic user-events.test
kafka-acls --bootstrap-server localhost:9092 --add --allow-principal User:test_consumer --operation Read --group test-consumer-group

echo "Listing ACLs..."
kafka-acls --bootstrap-server localhost:9092 --list

echo "Kafka setup complete!"
