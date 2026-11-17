#!/usr/bin/env ruby

require "yaml"

workflow_path = File.expand_path("../.github/workflows/ci.yml", __dir__)
workflow = YAML.load_file(workflow_path)
triggers = workflow["on"] || workflow[true]
dispatch = triggers&.fetch("workflow_dispatch", nil)
inputs = dispatch&.fetch("inputs", nil)
target = inputs&.fetch("deploy_target", nil)

unless target == {
  "description" => "Deployment target",
  "type" => "choice",
  "options" => %w[admin customer customer-web],
  "required" => true
}
  abort "workflow_dispatch must require deploy_target choice with admin, customer, and customer-web options"
end

jobs = workflow.fetch("jobs")
expected_conditions = {
  "deploy-dev" => "github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/main' && inputs.deploy_target == 'customer'",
  "deploy-admin-dev" => "github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/main' && inputs.deploy_target == 'admin'",
  "deploy-customer-web-dev" => "github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/main' && inputs.deploy_target == 'customer-web'"
}

expected_conditions.each do |job_name, expected|
  actual = jobs.fetch(job_name).fetch("if", nil)
  abort "#{job_name} condition differs from the fail-closed deployment policy" unless actual == expected
end

def deploys?(job_condition, event_name:, ref:, target:)
  return false unless event_name == "workflow_dispatch" && ref == "refs/heads/main"

  case job_condition
  when :customer
    target == "customer"
  when :admin
    target == "admin"
  when :customer_web
    target == "customer-web"
  else
    false
  end
end

[
  ["admin", true, false, false],
  ["customer", false, true, false],
  ["customer-web", false, false, true],
  ["", false, false, false],
  ["invalid", false, false, false]
].each do |target_value, expected_admin, expected_customer, expected_customer_web|
  actual_admin = deploys?(:admin, event_name: "workflow_dispatch", ref: "refs/heads/main", target: target_value)
  actual_customer = deploys?(:customer, event_name: "workflow_dispatch", ref: "refs/heads/main", target: target_value)
  actual_customer_web = deploys?(:customer_web, event_name: "workflow_dispatch", ref: "refs/heads/main", target: target_value)
  unless [actual_admin, actual_customer, actual_customer_web] == [expected_admin, expected_customer, expected_customer_web]
    abort "deployment target #{target_value.inspect} did not select exactly the expected job"
  end
end

%w[admin customer customer-web].each do |target_value|
  actual_admin = deploys?(:admin, event_name: "push", ref: "refs/heads/main", target: target_value)
  actual_customer = deploys?(:customer, event_name: "workflow_dispatch", ref: "refs/heads/feature", target: target_value)
  abort "event/ref protections failed for target #{target_value}" if actual_admin || actual_customer
end

puts "Deployment target workflow policy passed."
