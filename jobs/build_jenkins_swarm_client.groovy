import util.Plumber
import org.yaml.snakeyaml.Yaml

def sqre = new Yaml().load(readFileFromWorkspace('etc/sqre/config.yaml'))

def p = new Plumber(name: 'sqre/infra/build-jenkins-swarm-client', dsl: this)
p.pipeline().with {
  description('Constructs docker jenkins swarm client images.')

  parameters {
    stringParam('SWARM_VER', sqre.jenkins_swarm_client.docker_registry.tag, 'swarm client version.')
    booleanParam('NO_PUSH', false, 'Do not push image to docker registry.')
    booleanParam('LATEST', false, 'Also push to docker registry with "latest" tag.')
  }
}
