# Seed Jobs for Jenkins

This directory is for seed jobs for jenkins. Whilst it is in theory possible to
configure seed jobs via the helm file, it seems the groovy inside yaml inside
yaml breaks, and so having the groovy files be separate is a smarter thing to
do.

It appears that the original seed job system was based on
<https://github.com/sheehan/job-dsl-gradle-example/>. It is worth referring to
that repository to understand how seed jobs are set up.

## Why use Helm to set up Jenkins

As per <https://www.jenkins.io/doc/book/installing/kubernetes/>, there are three
ways of installing jenkins into a kubernetes cluster:

* Use the kubernetes operator
* Use the provided helm chart
* Manually define the setup using standard kubernetes objects

Whilst using the kubernetes operator would be ideal, the way it is currently set
up requires that all plugins and jobs are predefined, and there appears to be no
long term storage of config or runs. As jenkins plugins can be quite finicky,
this involves large amounts of trial and error.

Whilst manually setting up the system might give more control of the system, it
does involve more maintenance overhead than the other two options, and does not
allow as easy configuration of the system.

## Setting up Helm for installing Jenkins

See <https://helm.sh/docs/intro/install/> for how to install helm.

Once helm is installed, you need to add the helm repository containing the
jenkins helm charts:

```
helm repo add jenkinsci <https://charts.jenkins.io>
```

This should now appear on the list of installed repositories that appear by
running `helm repo list`.

## Installing Jenkins via Helm

As per <https://helm.sh/docs/intro/using_helm/>, running:

```
helm install -n <namespace_to_use> <install_name> jenkinsci/jenkins -f <config>
```

will install the jenkins helm chart with the config that has been specific in
the given files.

You should not need to modify the files too much, but there are certain sections
you will want to be familiar with:

* `installPlugins` and `additionalPlugins`: Plugins needed for the system.
   Jenkins is a bit picky about versions, so you may need to work out which
   plugins are leaf plugins and install those, rather than trying to lock
   everything.
* `JCasC`: This is where jenkins config is injected. Things like security
   properties, seed jobs and authentication are configured here.
* `ingress`: This is where we configure external access to jenkins.

# Updating Jenkins - step-by-step guide: 

## Backing up Current Jenkins State:
Before Jenkins can be properly updated, the state must be backed up. Jenkins state is currently stored in a tarball at s3df under the directory `/sdf/home/r/ranabhat/prod_jenkins/`. 
* Jenkins persistent volume (state) is in the directory `/var/jenkins_home` on the pod. To preserve the state, we tar the contents of `jenkins_home` to SLAC s3df. The transfer will take about an hour.
* Check that you have `ssh` access to s3df with:
  *  __ssh *YOUR_USERNAME*@sdfdtn001.slac.stanford.edu__
  
1. Ensure that no jobs are running at <https://rubin-ci.slac.stanford.edu/>. If there are jobs running during an update, cancel those jobs and notify the owners of the jobs via the slack channel `dm-jenkins`. Ensure the jobs cancel completely before beginning the backup. 
2. On cluster `rubin-jenkins-control`, `exec` into the jenkins container on the production pod:
```
k exec prod-jenkins-0 -n jenkins-prod -it -- sh
```
3. Move to the `jenkins_home` directory: `cd /var/jenkins_home`
4. Tar the contents of the folder (excluding the . and .. directories) to s3df. Replace YOUR_USERNAME and DATE appropriately in the code block below:
```
tar czf -  --directory=/var/jenkins_home --exclude=. --exclude=.. * .* | ssh  YOUR_USERNAME@sdfdtn001.slac.stanford.edu 'cat > /sdf/data/rubin/user/ranabhat/prod_jenkins/prod_jenkins_home_DATE.tar.gz'
```
5. In a new terminal, `ssh` into the directory at s3df and do `ls -lah` to check that the contents are being copied over.
