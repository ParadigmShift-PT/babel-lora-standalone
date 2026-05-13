all: build run

build:
	mvn clean package

run:
	java -jar target/babel.lora-0.2.0.jar

