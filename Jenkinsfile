pipeline {
  agent{ label 'docker' }
  stages {
    stage('Test') {
      parallel {
        stage('unit'){
          steps { sh 'sh jenkins/unit-tests.sh' }
        }
        stage('checkstyle'){
          steps { sh 'sh jenkins/lint.sh' }
        }
        stage('rspec-postgresql'){
          environment{
            CANDLEPIN_DATABASE = 'postgresql'
            CP_TEST_ARGS = '-r'
          }
          steps { sh 'sh jenkins/rspec-tests.sh' }
        }
        stage('rspec-mysql'){
          environment{
            CANDLEPIN_DATABASE = 'mysql'
            CP_TEST_ARGS = '-r'
          }
          steps { sh 'sh jenkins/rspec-tests.sh' }
        }
        stage('rspec-postgres-hosted'){
          environment{
            CANDLEPIN_DATABASE = 'postgresql'
            CP_TEST_ARGS = '-H'
          }
          steps { sh 'sh jenkins/rspec-tests.sh' }
        }
        stage('rspec-mysql-hosted'){
          environment{
            CANDLEPIN_DATABASE = 'mysql'
            CP_TEST_ARGS = '-H'
          }
          steps { sh 'sh jenkins/rspec-tests.sh' }
        }
        stage('rspec-qpid'){
          environment{
            CANDLEPIN_DATABASE = 'postgresql'
            CP_TEST_ARGS = '-q -r qpid_spec'
          }
          steps { sh 'sh jenkins/rspec-tests.sh' }
        }
        stage('bugzilla-reference'){
          environment{
            GITHUB_TOKEN = credentials('github-api-token-as-username-password')
          }
          steps { sh 'python jenkins/check_pr_branch.py $CHANGE_ID' }
        }
      }
    }
  }
}