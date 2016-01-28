require "perfect_retry"

module Embulk
  module Input
    module Zendesk
      class Plugin < InputPlugin
        ::Embulk::Plugin.register_input("zendesk", self)

        def self.transaction(config, &control)
          task = config_to_task(config)
          client = Client.new(task)
          client.validate_config

          columns = task[:schema].map do |column|
            name = column["name"]
            type = column["type"].to_sym

            Column.new(nil, name, type, column["format"])
          end

          if task[:incremental] && !Client::AVAILABLE_INCREMENTAL_EXPORT.include?(task[:target])
            Embulk.logger.warn "target: #{task[:target]} don't support incremental export API. Will be ignored start_time option"
          end

          resume(task, columns, 1, &control)
        end

        def self.resume(task, columns, count, &control)
          task_reports = yield(task, columns, count)
          report = task_reports.first

          next_config_diff = {}
          if report[:start_time]
            next_config_diff[:start_time] = report[:start_time]
          end
          return next_config_diff
        end

        def self.guess(config)
          task = config_to_task(config)
          client = Client.new(task)
          client.validate_config

          records = []
          client.public_send(task[:target]) do |record|
            records << record
          end

          columns = Guess::SchemaGuess.from_hash_records(records).map do |column|
            hash = column.to_h
            hash.delete(:index)
            hash.delete(:format) unless hash[:format]

            # NOTE: Embulk 0.8.1 guesses Hash and Hashes in Array as string.
            #       https://github.com/embulk/embulk/issues/379
            #       This is workaround for that
            if records.any? {|r| [Array, Hash].include?(r[hash[:name]].class) }
              hash[:type] = :json
            end

            # NOTE: current version don't support JSON type
            next if hash[:type] == :json

            hash
          end

          return {"columns" => columns.compact}
        end

        def self.config_to_task(config)
          {
            login_url: config.param("login_url", :string),
            auth_method: config.param("auth_method", :string, default: "basic"),
            target: config.param("target", :string),
            username: config.param("username", :string, default: nil),
            password: config.param("password", :string, default: nil),
            token: config.param("token", :string, default: nil),
            access_token: config.param("access_token", :string, default: nil),
            start_time: config.param("start_time", :string, default: nil),
            retry_limit: config.param("retry_limit", :integer, default: 5),
            retry_initial_wait_sec: config.param("retry_initial_wait_sec", :integer, default: 1),
            incremental: config.param("incremental", :bool, default: true),
            schema: config.param(:columns, :array, default: []),
          }
        end

        def init
          @start_time = Time.parse(task[:start_time]) if task[:start_time]
        end

        def run
          method = task[:target]
          args = [preview?]
          if !preview? && @start_time
            args << @start_time.to_i
          end

          client = Client.new(task)
          last_data = client.public_send(method, *args) do |record|
            values = extract_values(record)
            page_builder.add(values)
          end
          page_builder.finish

          task_report = {}
          if task[:incremental] && last_data && last_data["end_time"]
            # NOTE: start_time compared as "=>", not ">".
            #       If we will use end_time for next start_time, we got the same record that is last fetched
            #       end_time + 1 is workaround for that
            next_start_time = Time.at(last_data["end_time"] + 1)
            task_report[:start_time] = next_start_time.strftime("%Y-%m-%d %H:%M:%S%z")
          end

          return task_report
        end

        private

        def preview?
          org.embulk.spi.Exec.isPreview()
        rescue java.lang.NullPointerException => e
          false
        end

        def extract_values(record)
          values = task[:schema].map do |column|
            value = record[column["name"].to_s]
            next if value.nil?
            cast(value, column["type"].to_s)
          end

          values
        end

        def cast(value, type)
          case type
          when "timestamp"
            Time.parse(value)
          when "double"
            Float(value)
          when "long"
            Integer(value)
          when "boolean"
            !!value
          when "string"
            value.to_s
          else
            value
          end
        end
      end

    end
  end
end
