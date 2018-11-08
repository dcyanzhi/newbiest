import React, { Component } from 'react';
import { Table, Popconfirm, Button, Icon, Divider,Form } from 'antd';
import { Link } from 'react-router-dom';
import './EntityListTable.scss';
import {Application} from '../../api/Application'
import {DefaultRowKey, Type} from '../../api/const/ConstDefine'
import TableManagerRequestBody from '../../api/table-manager/TableManagerRequestBody';
import TableManagerRequestHeader from '../../api/table-manager/TableManagerRequestHeader';
import Request from '../../api/Request';
import {UrlConstant} from "../../api/const/ConstDefine";
import MessageUtils from '../../api/utils/MessageUtils';
import Field from '../../api/dto/ui/Field';
import EntityForm from '../Form/EntityForm';
import * as PropTypes from 'prop-types';

export default class EntityListTable extends Component {

    static displayName = 'EntityListTable';

    constructor(props) {
        super(props);
        this.state = {
            tableRrn: this.props.tableRrn,
            columns: [],
            data: [],
            pagination: this.props.pagination == null ? Application.table.pagination : this.props.pagination,
            rowkey: this.props.rowkey == null ? DefaultRowKey : this.props.rowkey,
            rowClassName: (record, index) => {},
            loading: true,
            // 是否带有选择框
            check: this.props.check,
            rowSelection: undefined,
            selectedRowKeys: [],
            selectedRows: [],
            formVisible: false,
            fields: [],
            editorObject: undefined
        };
    }

    componentWillMount = () => {
        this.setState({
            rowClassName: (record, index) => this.getRowClassName(record, index),
            rowSelection: this.state.check ? this.getRowSelection() : undefined, 
            loading: true,
        });
    }

    componentDidMount() {
        this.buildTable(this.state.tableRrn);
    }
    
    getRowClassName = (record, index) => {
        if(index % 2 ===0) {
            return 'even-row'; 
        } else {
            return ''; 
        }
    };

    // 默认的table框的选择框属性
    getRowSelection = () => {
        const rowSelection = {
            columnWidth: 10,
            fixed: true,
            onChange: (selectedRowKeys, selectedRows) => {
                this.setState({
                    selectedRowKeys: selectedRowKeys,
                    selectedRows: selectedRows
                })
            }
        }
        return rowSelection;
    }
    
    buildTable = (tableRrn) => {
        const self = this;
        let requestBody = TableManagerRequestBody.buildGetData(tableRrn);
        let requestHeader = new TableManagerRequestHeader();
        let request = new Request(requestHeader, requestBody, UrlConstant.TableMangerUrl);
        let requestObject = {
            request: request,
            success: function(responseBody) {
                let fields = responseBody.table.fields;
                let columnData = self.buildColumn(fields);
                self.setState({
                    data: responseBody.dataList,
                    columns: columnData.columns,
                    loading: false,
                    fields: fields
                });                
            }
          }
        MessageUtils.sendRequest(requestObject);
    }

    buildColumn = (fields) => {
        let columns = [];
        for (let field of fields) {
            let f  = new Field(field);
            let column = f.buildColumn();
            if (column != null) {
                columns.push(column);
            }
        }
        let oprationColumn = this.buildOprationColumn();
        columns.push(oprationColumn);

        // 根据长度算宽度才能保证fixed栏位不重复出现
        for (let column of columns) {
            column.width = Application.table.scroll.x / columns.length;
        }
        return {
            columns: columns,
        };
    }

    buildOprationColumn() {
        let self = this;
        let oprationColumn = {
            key: "opration",
            title: "opration",
            dataIndex: "opration",
            align: "center",
            fixed: 'right',
            render: (text, record) => {
                return (
                    <div>
                        <Button style={{marginRight:'1px'}} icon="form" onClick={() => self.handleEdit(record)} size="small" href="javascript:;">编辑</Button>
                        <Popconfirm title="Sure to delete?">
                            <Button icon="delete" size="small" type="danger">删除</Button>
                        </Popconfirm>
                    </div>
                );
            }
        };
        return oprationColumn;
    }

    handleEdit(record) {
        this.setState({
            formVisible : true,
            editorObject: record
        })
        console.log(record);
    }

    handleSave = (e) => {
        const form = this.form;
        form.validateFields((err, values) => {
            if (err) {
                return;
            }
            //TODO 处理保存。注意copy值的问题
            this.setState({
                formVisible: false
            })
        });
        
    }

    handleCancel = (e) => {
        this.setState({
            formVisible: false
        })
    }

    formRef = (form) => {
        this.form = form;
    };

    render() {
        const {data, pagination, columns, rowkey, loading, rowClassName, rowSelection} = this.state;
        const WrappedAdvancedEntityForm = Form.create()(EntityForm);
        if(data.length >= Application.scrollNum){
            Application.table.scroll.y = Application.tableY
        }
        return (
          <div >
            <Link to="" style={styles.tableButton}>
                <Button type="primary" icon="plus">添加内容</Button>
            </Link>
            <div style={styles.tableContainer}>
                <Table
                dataSource={data}
                bordered
                className="custom-table"
                pagination={pagination}
                columns = {columns}
                scroll = {Application.table.scroll}
                rowKey = {rowkey}
                loading = {loading}
                rowClassName = {rowClassName.bind(this)}
                rowSelection = {rowSelection}
                >
                </Table>
                <WrappedAdvancedEntityForm ref={this.formRef} object={this.state.editorObject} visible={this.state.formVisible} fields={this.state.fields}
                    onOk={this.handleSave} onCancel={this.handleCancel} />
            </div>
          </div>
        );
    }
}
EntityListTable.prototypes = {
    tableRrn: PropTypes.number.isRequired,
    check: PropTypes.bool,
    rowClassName: PropTypes.func,
    rowkey: PropTypes.string,
    pagination: PropTypes.pagination
}

const styles = {
    tableContainer: {
      background: '#fff',
      paddingBottom: '10px',
    },
    highlightRow : {
      backgroundo: '#ff0000'
    },
    editIcon: {
      color: '#999',
      cursor: 'pointer',
    },
    circle: {
      display: 'inline-block',
      background: '#28a745',
      width: '8px',
      height: '8px',
      borderRadius: '50px',
      marginRight: '4px',
    },
    stateText: {
      color: '#28a745',
    },
    opration: {
      width: '100%'
    },
    tableButton: {
        position:'absolute',
        top:'120px',
        right:'120px'
    }
};