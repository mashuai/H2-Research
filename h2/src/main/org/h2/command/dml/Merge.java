/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.command.dml;

import java.util.ArrayList;

import org.h2.api.ErrorCode;
import org.h2.api.Trigger;
import org.h2.command.Command;
import org.h2.command.CommandInterface;
import org.h2.command.Prepared;
import org.h2.engine.Right;
import org.h2.engine.Session;
import org.h2.engine.UndoLogRecord;
import org.h2.expression.Expression;
import org.h2.expression.Parameter;
import org.h2.index.Index;
import org.h2.message.DbException;
import org.h2.result.ResultInterface;
import org.h2.result.Row;
import org.h2.table.Column;
import org.h2.table.Table;
import org.h2.util.New;
import org.h2.util.StatementBuilder;
import org.h2.value.Value;

/**
 * This class represents the statement
 * MERGE
 */
//先更新，如果没有记录被更新，说明是一条新的记录，接着再插入
public class Merge extends Prepared {

    private Table table;
    private Column[] columns;
    private Column[] keys;
    private final ArrayList<Expression[]> list = New.arrayList();
    private Query query;
    private Prepared update;

    public Merge(Session session) {
        super(session);
    }

    @Override
    public void setCommand(Command command) {
        super.setCommand(command);
        if (query != null) {
            query.setCommand(command);
        }
    }

    public void setTable(Table table) {
        this.table = table;
    }

    public void setColumns(Column[] columns) {
        this.columns = columns;
    }

    public void setKeys(Column[] keys) {
        this.keys = keys;
    }

    public void setQuery(Query query) {
        this.query = query;
    }

    /**
     * Add a row to this merge statement.
     *
     * @param expr the list of values
     */
    public void addRow(Expression[] expr) {
        list.add(expr);
    }

    @Override
    public int update() {
        int count;
        session.getUser().checkRight(table, Right.INSERT);
        session.getUser().checkRight(table, Right.UPDATE);
        setCurrentRowNumber(0);
        if (list.size() > 0) {
            count = 0;
            for (int x = 0, size = list.size(); x < size; x++) {
                setCurrentRowNumber(x + 1);
                Expression[] expr = list.get(x);
                Row newRow = table.getTemplateRow();
                for (int i = 0, len = columns.length; i < len; i++) {
                    Column c = columns[i];
                    int index = c.getColumnId();
                    Expression e = expr[i];
                    if (e != null) {
                        // e can be null (DEFAULT)
                        try {
                            Value v = c.convert(e.getValue(session));
                            newRow.setValue(index, v);
                        } catch (DbException ex) {
                            throw setRow(ex, count, getSQL(expr));
                        }
                    }
                }
                merge(newRow);
                count++;
            }
        } else {
            ResultInterface rows = query.query(0);
            count = 0;
            table.fire(session, Trigger.UPDATE | Trigger.INSERT, true);
            table.lock(session, true, false);
            while (rows.next()) {
                count++;
                Value[] r = rows.currentRow();
                Row newRow = table.getTemplateRow();
                setCurrentRowNumber(count);
                for (int j = 0; j < columns.length; j++) {
                    Column c = columns[j];
                    int index = c.getColumnId();
                    try {
                        Value v = c.convert(r[j]);
                        newRow.setValue(index, v);
                    } catch (DbException ex) {
                        throw setRow(ex, count, getSQL(r));
                    }
                }
                merge(newRow);
            }
            rows.close();
            table.fire(session, Trigger.UPDATE | Trigger.INSERT, false);
        }
        return count;
    }

    private void merge(Row row) {
        ArrayList<Parameter> k = update.getParameters();
        for (int i = 0; i < columns.length; i++) {
            Column col = columns[i];
            Value v = row.getValue(col.getColumnId());
            Parameter p = k.get(i);
            p.setValue(v);
        }
        for (int i = 0; i < keys.length; i++) {
            Column col = keys[i];
            Value v = row.getValue(col.getColumnId());
            if (v == null) {
                throw DbException.get(ErrorCode.COLUMN_CONTAINS_NULL_VALUES_1, col.getSQL());
            }
            Parameter p = k.get(columns.length + i);
            p.setValue(v);
        }
        //先更新，如果没有记录被更新，说明是一条新的记录，接着再插入
        int count = update.update();
        if (count == 0) {
            try {
                table.validateConvertUpdateSequence(session, row);
                boolean done = table.fireBeforeRow(session, null, row);
                if (!done) {
                    table.lock(session, true, false);
                    table.addRow(session, row);
                    session.log(table, UndoLogRecord.INSERT, row);
                    table.fireAfterRow(session, null, row, false);
                }
            } catch (DbException e) {
                if (e.getErrorCode() == ErrorCode.DUPLICATE_KEY_1) {
                    // possibly a concurrent merge or insert
                    Index index = (Index) e.getSource();
                    if (index != null) {
                        // verify the index columns match the key
                        Column[] indexColumns = index.getColumns();
                        boolean indexMatchesKeys = true;
                        if (indexColumns.length <= keys.length) {
                            for (int i = 0; i < indexColumns.length; i++) {
                                if (indexColumns[i] != keys[i]) {
                                    indexMatchesKeys = false;
                                    break;
                                }
                            }
                        }
                        if (indexMatchesKeys) {
                            throw DbException.get(ErrorCode.CONCURRENT_UPDATE_1, table.getName());
                        }
                    }
                }
                throw e;
            }
        } else if (count != 1) {
            throw DbException.get(ErrorCode.DUPLICATE_KEY_1, table.getSQL());
        }
    }

    @Override
    public String getPlanSQL() {
        StatementBuilder buff = new StatementBuilder("MERGE INTO ");
        buff.append(table.getSQL()).append('(');
        for (Column c : columns) {
            buff.appendExceptFirst(", ");
            buff.append(c.getSQL());
        }
        buff.append(')');
        if (keys != null) {
            buff.append(" KEY(");
            buff.resetCount();
            for (Column c : keys) {
                buff.appendExceptFirst(", ");
                buff.append(c.getSQL());
            }
            buff.append(')');
        }
        buff.append('\n');
        if (list.size() > 0) {
            buff.append("VALUES ");
            int row = 0;
            for (Expression[] expr : list) {
                if (row++ > 0) {
                    buff.append(", ");
                }
                buff.append('(');
                buff.resetCount();
                for (Expression e : expr) {
                    buff.appendExceptFirst(", ");
                    if (e == null) {
                        buff.append("DEFAULT");
                    } else {
                        buff.append(e.getSQL());
                    }
                }
                buff.append(')');
            }
        } else {
            buff.append(query.getPlanSQL());
        }
        return buff.toString();
    }

    @Override
    public void prepare() {
        if (columns == null) {
        	//例如: MERGE INTO MergeTest VALUES()
        	//这种情况没用的，应该抛异常才对，否则下面生成update语句时会出错，像这样:
        	//UPDATE PUBLIC.MERGETEST SET  WHERE ID=?
            if (list.size() > 0 && list.get(0).length == 0) {
                // special case where table is used as a sequence
                columns = new Column[0];
            } else {
                columns = table.getColumns(); //如: MERGE INTO MergeTest(SELECT * FROM tmpSelectTest)
            }
        }
        if (list.size() > 0) {
            for (Expression[] expr : list) {
                if (expr.length != columns.length) {
                    throw DbException.get(ErrorCode.COLUMN_COUNT_DOES_NOT_MATCH);
                }
                for (int i = 0; i < expr.length; i++) {
                    Expression e = expr[i];
                    if (e != null) {
                        expr[i] = e.optimize(session);
                    }
                }
            }
        } else {
            query.prepare();
            if (query.getColumnCount() != columns.length) {
                throw DbException.get(ErrorCode.COLUMN_COUNT_DOES_NOT_MATCH);
            }
        }
        if (keys == null) { //如果没有指定key，表里必须有主键字段
            Index idx = table.getPrimaryKey();
            if (idx == null) { //org.h2.table.Table.getPrimaryKey()里已处理null的情况了，除非有字类覆盖它
                throw DbException.get(ErrorCode.CONSTRAINT_NOT_FOUND_1, "PRIMARY KEY");
            }
            keys = idx.getColumns();
        }
        //例如: UPDATE PUBLIC.MERGETEST SET ID=?, NAME=? WHERE ID=?
        //columns就是要更新的字段，keys当成where条件且用and拼装
        StatementBuilder buff = new StatementBuilder("UPDATE ");
        buff.append(table.getSQL()).append(" SET ");
        for (Column c : columns) {
            buff.appendExceptFirst(", ");
            buff.append(c.getSQL()).append("=?");
        }
        buff.append(" WHERE ");
        buff.resetCount();
        for (Column c : keys) {
            buff.appendExceptFirst(" AND ");
            buff.append(c.getSQL()).append("=?");
        }
        String sql = buff.toString();
        update = session.prepare(sql);
    }

    @Override
    public boolean isTransactional() {
        return true;
    }

    @Override
    public ResultInterface queryMeta() {
        return null;
    }

    @Override
    public int getType() {
        return CommandInterface.MERGE;
    }

    @Override
    public boolean isCacheable() {
        return true;
    }

}
