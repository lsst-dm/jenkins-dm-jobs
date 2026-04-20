import util.Plumber
import org.yaml.snakeyaml.Yaml

def scipipe = new Yaml().load(readFileFromWorkspace('etc/scipipe/build_matrix.yaml'))

def p = new Plumber(name: 'sqre/infra/build_docker_newinstall', dsl: this)
p.pipeline().with {
  description('Constructs docker docker-newinstall images.')

  parameters {
    booleanParam('NO_PUSH', false, 'Do not push image to docker registry.')
    stringParam('SPLENV_REF', scipipe.template.splenv_ref, 'conda env ref')
  }
}
