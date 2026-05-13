all: build run

build:
	mvn clean package -P executable

run:
	java -jar target/babel-lora-0.2.0-executable.jar

