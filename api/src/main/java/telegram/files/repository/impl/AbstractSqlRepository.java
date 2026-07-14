package telegram.files.repository.impl;

import io.vertx.sqlclient.PreparedQuery;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import telegram.files.repository.SqlUtils;

public abstract class AbstractSqlRepository {

    protected final SqlClient sqlClient;

    public AbstractSqlRepository(SqlClient sqlClient) {
        this.sqlClient = sqlClient;
    }

    protected PreparedQuery<RowSet<Row>> preparedQuery(String sql) {
        return sqlClient.preparedQuery(SqlUtils.sql(sql));
    }

    protected PreparedQuery<RowSet<Row>> preparedQuery(SqlClient client, String sql) {
        return client.preparedQuery(SqlUtils.sql(sql));
    }
}

