import util.Plumber
import org.yaml.snakeyaml.Yaml

def sqre = new Yaml().load(readFileFromWorkspace('etc/sqre/config.yaml'))

def p = new Plumber(name: 'sqre/infra/build-dind', dsl: this)
p.pipeline().with {
  description('Constructs docker dind images.')

  parameters {
    stringParam('DIND_VER', sqre.dind.docker_registry.tag, 'dind version.')
    booleanParam('NO_PUSH', false, 'Do not push image to docker registry.')
    booleanParam('LATEST', false, 'Also push to docker registry with "latest" tag.')
  }
}
