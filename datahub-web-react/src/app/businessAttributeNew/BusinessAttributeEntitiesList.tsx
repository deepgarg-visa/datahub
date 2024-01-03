import React from 'react';
import styled from 'styled-components/macro';
// import { BusinessAttribute } from '../../types.generated';
import BusinessAttributeEntityItem from './BusinessAttributeEntityItem';

const EntitiesWrapper = styled.div`
    flex: 1;
    overflow: auto;
    padding-bottom: 20px;
`;

interface Props {
    businessAttributes: any[];
}

function BusinessAttributeEntitiesList(props: Props) {
    const { businessAttributes } = props;

    return (
        <EntitiesWrapper>
            {businessAttributes.map((businessAttribute) => (
                <BusinessAttributeEntityItem
                    name={businessAttribute.properties?.name || ''}
                    urn={businessAttribute.urn}
                    type={businessAttribute.type}
                />
            ))}
        </EntitiesWrapper>
    );
}

export default BusinessAttributeEntitiesList;
