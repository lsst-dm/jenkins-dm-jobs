jenkins-dm-jobs
===

When updating a job in this repo, for example when changing the user-specified
parameters but _not_ when changing the pipeline to be run, manually trigger
the sqre/seeds/dm-jobs job to rebuild the interface.

[![Build Status](https://travis-ci.org/lsst-dm/jenkins-dm-jobs.png)](https://travis-ci.org/lsst-dm/jenkins-dm-jobs)

## Installing Jenkins

`seeds` contains a README plus a helm values file for deploying a Jenkins
controller at SLAC. Read that README for more details.

`lsst-jenkins-swarm-agent` contains a helm chart to run a Linux Jenkins Agent
and associated containers needed for tests/package. Documentation for this has
not yet been created (you'll need to look at the individual templates).
