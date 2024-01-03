import React, { useState } from 'react';
import { message, Button, Input, Modal, Typography, Form, Select } from 'antd';
import styled from 'styled-components';
import { useEnterKeyListener } from '../shared/useEnterKeyListener';
import { useCreateBusinessAttributeMutation } from '../../graphql/businessAttribute.generated';
import { CreateBusinessAttributeInput, SchemaFieldDataType, EntityType } from '../../types.generated';
import analytics, { EventType } from '../analytics';
import { useEntityRegistry } from '../useEntityRegistry';

type Props = {
    visible: boolean;
    onClose: () => void;
};

type FormProps = {
    name: string;
    description?: string;
    dataType?: SchemaFieldDataType;
};

const DataTypeSelectContainer = styled.div`
    padding: 1px;
`;

const DataTypeSelect = styled(Select)`
    && {
        width: 100%;
        margin-top: 1em;
        margin-bottom: 1em;
    }
`;

// Ensures that any newly added datatype is automatically included in the user dropdown.
const DATA_TYPES = Object.values(SchemaFieldDataType);

export default function CreateBusinessAttributeModal({ visible, onClose }: Props) {
    const [createButtonEnabled, setCreateButtonEnabled] = useState(true);

    const [createBusinessAttribute] = useCreateBusinessAttributeMutation();

    const [form] = Form.useForm<FormProps>();

    const entityRegistry = useEntityRegistry();

    // Function to handle the close or cross button of Create Token Modal
    const onModalClose = () => {
        form.resetFields();
        onClose();
    };

    const onCreateNewToken = () => {
        const { name, description, dataType } = form.getFieldsValue();
        const input: CreateBusinessAttributeInput = {
            businessAttributeInfo: { name, description, type: dataType },
        };
        createBusinessAttribute({ variables: { input } })
            .then(() => {
                message.loading({ content: 'Updating...', duration: 2 });
                setTimeout(() => {
                    analytics.event({
                        type: EventType.CreateBusinessAttributeEvent,
                        name,
                    });
                    message.success({
                        content: `Created ${entityRegistry.getEntityName(EntityType.BusinessAttribute)}!`,
                        duration: 2,
                    });
                }, 2000);
            })
            .catch((e) => {
                message.destroy();
                message.error({ content: `Failed to create: \n ${e.message || ''}`, duration: 3 });
            });
        onModalClose();
    };

    // Handle the Enter press
    useEnterKeyListener({
        querySelectorToExecuteClick: '#createBusinessAttributeButton',
    });

    return (
        <>
            <Modal
                title="Create Business Attribute"
                visible={visible}
                onCancel={onModalClose}
                footer={
                    <>
                        <Button
                            onClick={onModalClose}
                            type="text"
                            data-testid="cancel-create-business-attribute-button"
                        >
                            Cancel
                        </Button>
                        <Button
                            id="createBusinessAttributeButton"
                            onClick={onCreateNewToken}
                            disabled={createButtonEnabled}
                            data-testid="create-business-attribute-button"
                        >
                            Create
                        </Button>
                    </>
                }
            >
                <Form
                    form={form}
                    layout="vertical"
                    onFieldsChange={() =>
                        setCreateButtonEnabled(form.getFieldsError().some((field) => field.errors.length > 0))
                    }
                >
                    <Form.Item label={<Typography.Text strong>Name</Typography.Text>}>
                        <Form.Item
                            name="name"
                            rules={[
                                {
                                    required: true,
                                    message: 'Enter a business attribute name.',
                                },
                                { whitespace: true },
                                { min: 1, max: 100 },
                            ]}
                            hasFeedback
                        >
                            <Input
                                placeholder="A name for business attribute"
                                data-testid="create-business-attribute-name"
                            />
                        </Form.Item>
                    </Form.Item>
                    <DataTypeSelectContainer>
                        <Form.Item label={<Typography.Text strong>Data Type</Typography.Text>}>
                            <Form.Item name="dataType" data-testid="select-data-type" noStyle>
                                <DataTypeSelect placeholder="A data type for business attribute">
                                    {DATA_TYPES.map((dataType: SchemaFieldDataType) => (
                                        <Select.Option key={dataType} value={dataType}>
                                            {dataType}
                                        </Select.Option>
                                    ))}
                                </DataTypeSelect>
                            </Form.Item>
                        </Form.Item>
                    </DataTypeSelectContainer>
                    <Form.Item label={<Typography.Text strong>Description</Typography.Text>}>
                        <Typography.Paragraph>
                            An optional description for your business attribute. You can change this later
                        </Typography.Paragraph>
                        <Form.Item name="description" rules={[{ whitespace: true }, { min: 1, max: 1000 }]} hasFeedback>
                            <Input.TextArea
                                placeholder="A description for your business attribute"
                                data-testid="create-business-attribute-description"
                            />
                        </Form.Item>
                    </Form.Item>
                </Form>
            </Modal>
        </>
    );
}
