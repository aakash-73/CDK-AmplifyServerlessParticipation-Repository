# Makefile for CDK Java Project

.PHONY: all build bootstrap list synth deploy

all: build bootstrap list synth deploy

build:
	mvn clean package

bootstrap:
	cdk bootstrap

list:
	cdk ls

synth:
	cdk synth

deploy:
	cdk deploy
