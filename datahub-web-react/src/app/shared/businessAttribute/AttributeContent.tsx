import styled from 'styled-components';
import { message, Modal, Tag } from 'antd';
import { GlobalOutlined } from '@ant-design/icons';
import React from 'react';
import Highlight from 'react-highlighter';
import { useEntityRegistry } from '../../useEntityRegistry';
import { BusinessAttributeAssociation, EntityType, SubResourceType } from '../../../types.generated';
import { useHasMatchedFieldByUrn } from '../../search/context/SearchResultContext';
import { MatchedFieldName } from '../../search/matches/constants';
import { useRemoveBusinessAttributeMutation } from '../../../graphql/mutations.generated';

const highlightMatchStyle = { background: '#ffe58f', padding: '0' };

const StyledAttribute = styled(Tag)<{ fontSize?: number; highlightAttribute?: boolean }>`
    &&& {
        ${(props) =>
            props.highlightAttribute &&
            `background: ${props.theme.styles['highlight-color']};
        border: 1px solid ${props.theme.styles['highlight-border-color']};
        `}
    }
    ${(props) => props.fontSize && `font-size: ${props.fontSize}px;`}
    cursor: pointer;
    display: flex;
    align-items: center;
    width: 100%;
    white-space: nowrap;
    text-overflow: ellipsis;
    overflow: hidden;
`;

const StyledGlobalOutlined = styled(GlobalOutlined)`
    margin-right: 4px;
`;

const StyledHighlight = styled(Highlight)`
    margin-left: 0;
    overflow: hidden;
    text-overflow: ellipsis;
`;

interface Props {
    businessAttribute: BusinessAttributeAssociation | undefined;
    entityUrn?: string;
    entitySubresource?: string;
    canRemove?: boolean;
    readOnly?: boolean;
    highlightText?: string;
    fontSize?: number;
    onOpenModal?: () => void;
    refetch?: () => Promise<any>;
}

export default function AttributeContent({
    businessAttribute,
    canRemove,
    readOnly,
    highlightText,
    fontSize,
    onOpenModal,
    entityUrn,
    entitySubresource,
    refetch,
}: Props) {
    const entityRegistry = useEntityRegistry();
    const [removeBusinessAttributeMutation] = useRemoveBusinessAttributeMutation();
    const highlightAttribute = useHasMatchedFieldByUrn(
        businessAttribute?.businessAttribute?.urn || '',
        'businessAttributes' as MatchedFieldName,
    );

    const removeAttribute = (attributeToRemove: BusinessAttributeAssociation) => {
        onOpenModal?.();
        const AttributeName =
            attributeToRemove &&
            entityRegistry.getDisplayName(
                attributeToRemove.businessAttribute.type,
                attributeToRemove.businessAttribute,
            );
        Modal.confirm({
            title: `Do you want to remove ${AttributeName} attribute?`,
            content: `Are you sure you want to remove the ${AttributeName} attribute?`,
            onOk() {
                if (attributeToRemove.associatedUrn || entityUrn) {
                    removeBusinessAttributeMutation({
                        variables: {
                            input: {
                                businessAttributeUrn: attributeToRemove.businessAttribute.urn,
                                resourceUrn: {
                                    resourceUrn: attributeToRemove.associatedUrn || entityUrn || '',
                                    subResource: entitySubresource,
                                    subResourceType: entitySubresource ? SubResourceType.DatasetField : null,
                                },
                            },
                        },
                    })
                        .then(({ errors }) => {
                            if (!errors) {
                                message.success({ content: 'Removed Business Attribute!', duration: 2 });
                            }
                        })
                        .then(refetch)
                        .catch((e) => {
                            message.destroy();
                            message.error({
                                content: `Failed to remove business attribute: \n ${e.message || ''}`,
                                duration: 3,
                            });
                        });
                }
            },
            onCancel() {},
            okText: 'Yes',
            maskClosable: true,
            closable: true,
        });
    };

    const handleRemoveAttribute = (e: React.MouseEvent) => {
        e.preventDefault();
        removeAttribute(businessAttribute as BusinessAttributeAssociation);
    };

    return (
        <StyledAttribute
            closable={canRemove && !readOnly}
            onClose={handleRemoveAttribute}
            fontSize={fontSize}
            highlightAttribute={highlightAttribute}
        >
            <StyledGlobalOutlined />
            <StyledHighlight matchStyle={highlightMatchStyle} search={highlightText}>
                {entityRegistry.getDisplayName(EntityType.BusinessAttribute, businessAttribute?.businessAttribute)}
            </StyledHighlight>
        </StyledAttribute>
    );
}
